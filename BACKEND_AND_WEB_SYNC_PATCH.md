# Apun Ka Communication — Separate Backend Migration & Web Tick Sync Patch

This document provides the isolated backend migration (`PocketBase/Go`) and web (`web/src.js`, `web/style.css`) updates required so that **Web (`chat.tmadhu.me`) and Android (`me.tmadhu.mandychat`) share the exact same message tick behavior**:
- **`✓` (gray)**: Saved on the server (`POST /api/app/messages` succeeded).
- **`✓✓` (gray)**: Recipient's app has synced the message and acknowledged via `PUT /api/app/conversations/{id}/delivered` (`delivered_through`, `delivered_id`). Never inferred from FCM push enqueue or online status.
- **`✓✓` (colored)**: Recipient has viewed the message in an open chat via `PUT /api/app/conversations/{id}/read` (`read_through`, `read_id`).

> **Note**: The Android client already implements this contract and degrades gracefully if the server migration has not been deployed yet.

---

## 1. PocketBase Migration (`1794690000_delivered_watermarks.go`)

Add a new migration in your Go backend adding `delivered_through` (date) and `delivered_id` (text<=15) to `conversation_members`:

```go
package main

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

func init() {
	m.Register(func(app core.App) error {
		col, err := app.FindCollectionByNameOrId("conversation_members")
		if err != nil {
			return err
		}

		if col.Fields.GetByName("delivered_through") == nil {
			col.Fields.Add(&core.DateField{
				Name: "delivered_through",
			})
		}
		if col.Fields.GetByName("delivered_id") == nil {
			col.Fields.Add(&core.TextField{
				Name: "delivered_id",
				Max:  15,
			})
		}

		return app.Save(col)
	}, func(app core.App) error {
		col, err := app.FindCollectionByNameOrId("conversation_members")
		if err != nil {
			return nil
		}
		col.Fields.RemoveByName("delivered_through")
		col.Fields.RemoveByName("delivered_id")
		return app.Save(col)
	})
}
```

---

## 2. Authenticated Delivery Watermark Route (`messages.go`)

Register `PUT /api/app/conversations/{id}/delivered` alongside the existing `PUT /api/app/conversations/{id}/read` handler in `messages.go`:

```go
// Register in main.go / messages.go:
// se.Router.PUT("/api/app/conversations/{id}/delivered", handleMarkConversationDelivered(app))

func handleMarkConversationDelivered(app core.App) func(*core.RequestEvent) error {
	return func(re *core.RequestEvent) error {
		user := re.Auth
		if user == nil {
			return re.UnauthorizedError("Authentication required", nil)
		}
		convId := re.Request.PathValue("id")
		var body struct {
			Message string `json:"message"`
		}
		if err := re.BindBody(&body); err != nil || body.Message == "" {
			return re.BadRequestError("message ID is required", err)
		}

		msg, err := app.FindRecordById("messages", body.Message)
		if err != nil || msg.GetString("conversation") != convId {
			return re.NotFoundError("Message not found in conversation", err)
		}

		member, err := app.FindFirstRecordByFilter(
			"conversation_members",
			"conversation = {:conv} && user = {:user}",
			map[string]any{"conv": convId, "user": user.Id},
		)
		if err != nil {
			return re.ForbiddenError("Not a member of this conversation", err)
		}

		msgCreated := msg.GetDateTime("created")
		curDelivered := member.GetDateTime("delivered_through")
		curDeliveredId := member.GetString("delivered_id")

		// Advance monotonically by (created, id)
		shouldAdvance := curDelivered.IsZero() ||
			msgCreated.Time().After(curDelivered.Time()) ||
			(msgCreated.Time().Equal(curDelivered.Time()) && msg.Id > curDeliveredId)

		if shouldAdvance {
			member.Set("delivered_through", msgCreated)
			member.Set("delivered_id", msg.Id)
			if err := app.Save(member); err != nil {
				return re.InternalServerError("Failed to update delivered watermark", err)
			}
		}

		return re.NoContent(204)
	}
}
```

Also update `handleMarkConversationRead` so that marking a message `read` automatically ensures `(delivered_through, delivered_id)` is at least `(read_through, read_id)`.

---

## 3. Web Client Update (`web/src.js` & `web/style.css`)

1. **Acknowledge Synced Messages (`web/src.js`)**:
   - Whenever the web client receives a peer's message via `pb.collection('messages').subscribe('*')` or loads messages via REST, call:
     ```js
     await fetch(`/api/app/conversations/${conversationId}/delivered`, {
       method: "PUT",
       headers: {
         "Content-Type": "application/json",
         "Authorization": pb.authStore.token
       },
       body: JSON.stringify({ message: latestPeerMessage.id })
     });
     ```
2. **Replace Text Status Label with Compact Ticks (`web/src.js`)**:
   ```js
   function resolveMessageTick(msg, peerMember) {
     if (msg.pending || !msg.id) return { symbol: "⏳", cls: "tick-sending" };
     if (isAtOrBeforeCursor(msg, peerMember?.read_through, peerMember?.read_id)) {
       return { symbol: "✓✓", cls: "tick-seen" };
     }
     if (isAtOrBeforeCursor(msg, peerMember?.delivered_through, peerMember?.delivered_id)) {
       return { symbol: "✓✓", cls: "tick-delivered" };
     }
     return { symbol: "✓", cls: "tick-sent" };
   }
   ```
3. **Tick CSS (`web/style.css`)**:
   ```css
   .msg-tick {
     display: inline-block;
     margin-left: 4px;
     font-size: 0.75rem;
     font-weight: 700;
     letter-spacing: -1.5px;
   }
   .tick-sent, .tick-delivered {
     color: #94a3b8;
   }
   .tick-seen {
     color: #10b981;
   }
   ```
