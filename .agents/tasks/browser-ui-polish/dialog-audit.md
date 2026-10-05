# Dialog audit — Task 5 (radio / checkbox where it fits)

Scope: every `AlertDialog` in `BrowserActivity.kt` and the sheet-based choice UIs. The rule applied:

- A single, mutually-exclusive choice from a list → **radio** (`setSingleChoiceItems`).
- Several independent on/off options → **checkboxes**.
- Confirmation dialogs (message + confirm/cancel) and text-entry dialogs are **not** choice lists and
  are left as they are. Navigate-on-tap lists (each row is an action, not a selection) are left too.

## BrowserActivity.kt AlertDialogs

| Dialog | Builder shape | Classification | Action |
|---|---|---|---|
| User-Agent (`showUserAgentDialog`) | `setSingleChoiceItems` | Single exclusive choice | **Already a radio list — left unchanged.** |
| Home page (`promptHomePage`) | `setView` + `EditText` | Text entry | Left — not a choice list. |
| Custom User-Agent (`promptCustomUserAgent`) | `setView` + `EditText` | Text entry | Left — not a choice list. |
| Find in page (`promptFindInPage`) | `setView` + `EditText`, then a next/prev/close controller dialog | Text entry + action buttons | Left — not a choice list. |
| Reset saved site permissions (`confirmResetSitePermissions`) | `setMessage` + confirm/cancel | Confirmation | Left — not a choice list. |
| Delete cookies and site data (`confirmDeleteSiteData`) | `setMessage` + confirm/cancel | Confirmation | Left — not a choice list. |
| Clear browsing data (`confirmClearBrowsingData`) | `setMessage` + confirm/cancel | Confirmation | Left — not a choice list. |
| External sign-in (`promptExternalSignIn`) | `setMessage` + confirm/cancel | Confirmation | Left — not a choice list. |
| Downloads (`showDownloads`) | `setItems` with a `null` click listener + neutral "clear list" | Informational list (rows do nothing) | Left — not a choice list. |
| Bookmarks (`showBookmarks`) | `setAdapter`, each row navigates | Navigate-on-tap list | Left — each row is an action, not an exclusive selection. |
| History (`showHistory`) | `setAdapter`, each row navigates + neutral "clear all" | Navigate-on-tap list | Left — each row is an action, not an exclusive selection. |

## Sheet-based choice UIs

- **Settings sheet (`BrowserSettingsSheet`) segmented controls** (Appearance, etc.) already express a
  single exclusive choice with a selected marker ("✓") — the in-house equivalent of a radio. Left as-is.
- **Independent on/off options** (hide URL bar, pin URL bar, always-show floating button, DRM, ad-block,
  etc.) are already individual `Switch` rows in the Settings sheet, which is the checkbox-equivalent for
  independent toggles. No dialog hosts a multi-select list that should become checkboxes.
- **"Send to car" current-page row (`SendToCarSheet`)** uses a filled "◉" selected marker (a radio dot).
  It is a genuine single-selection indicator, not a chrome icon, and is left as text per the Task 3
  rule on keeping genuine content.

## Conclusion

The only exclusive-choice list is **User-Agent**, and it is already a radio (`setSingleChoiceItems`).
There are no multi-independent-toggle dialogs to convert to checkboxes — those live in the Settings sheet
as `Switch` rows. **No dialog code changes were required.**

Content was rephrased for compliance with licensing restrictions where source documentation was consulted.
