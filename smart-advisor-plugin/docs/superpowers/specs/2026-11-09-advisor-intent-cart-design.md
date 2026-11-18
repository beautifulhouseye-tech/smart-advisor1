# Smart Advisor 5.5.0 — Intent, Conversation State, Ranking, and Kitchen Cart

Date: 2026-11-09
Version target: 5.5.0
Status: approved for implementation planning after review amendments

## Problem

A live conversation log showed the advisor repeating the same catalog, ignoring topic changes, dropping brand follow-ups, and treating “build a cart” as another product listing.

Observed failures:

- “مستلزمات رجالية” after a skin-care thread still returned skin-care products.
- “بشرتي دهنية وأعاني من حبوب” returned a generic skin catalog, including dry-skin cream.
- “حذاء رجالي سكيتشرز” then “موديل أحدث” dropped Skechers and showed a random casual shoe.
- “نعم” and “اعمل سلة المشتريات لمستلزمات المطبخ” re-listed the same kitchen items instead of adding them to WooCommerce.

## Goal

The current chat turn must do exactly one of: switch category, apply a search filter, plan a kitchen list, add products to the WooCommerce cart, or continue the pending action. Never replay the same product ids when alternatives exist. Never add to cart unless the customer asked to build/add a cart or confirmed a pending add action.

## Non-goals

- No CRM, cross-device memory, or marketing messages.
- No automatic linking of WooCommerce orders or cart items to Smart Advisor projects.
- No reactivation of the unused module-manager system.
- No asking for budget or shoe size in chat (unchanged from 5.4.2).
- No adding the same product twice to the cart.
- No treating product views or wishlist adds as project progress.

## Architecture

Five cooperating units, each with one job:

1. **Intent Engine** (`Service_Conversation_Analyzer`) — classify the turn locally.
2. **Conversation State Manager** — hold pending action, last shown ids, and cart-plan progress.
3. **Smart Ranking Engine** (`Service_Smart_Search`) — filter and rank catalog results.
4. **Kitchen Cart Builder** (new helper used by the chat controller) — pick one product per kitchen subcategory and add it to WooCommerce when requested.
5. **Context and project isolation** — keep `known` scalars, conversation state, and project planner separate.

Call the LLM only when local classification cannot resolve type or intent.

## Intent model

Do not collapse category change, name filter, brand, and recency into one `switch_type` bucket.

| Concept | Example | Disposition |
|---|---|---|
| Category change | From skin care to kitchen tools | Clear previous type-specific `known`. Start the new type path. |
| Product-name filter | مستلزمات رجالية | Clear the previous type and its known values. Set `name_contains=رجالي`. Do not assign `أزياء`. Search titles first. |
| Brand refine | سكيتشرز | Keep shoe type. Set `brand=skechers`. |
| Recency refine | موديل أحدث | Keep brand. Set `recency=newest`. Re-rank. |
| Plan kitchen | تخطيط لشراء مستلزمات المطبخ / اقترح سلة للمطبخ | Show a kitchen plan. Do not add to cart. Set `pending_action=confirm_kitchen_cart`. |
| Build cart | اعمل سلة المشتريات / أضف هذه المنتجات | Add to WooCommerce cart. |
| Affirmation | نعم | Execute `pending_action` only. Never treat نعم as a standalone intent. |
| Greeting | مرحبا | Keep type, known, and conversation state. |

Local intent labels returned by the analyzer:

- `greeting`
- `switch_category`
- `refine`
- `plan_cart`
- `build_cart`
- `continue` (answer a required question, or execute `pending_action`)

“مستلزمات رجالية” is a name filter, not a fashion-category switch.

## Conversation state

`known_context.known` stays scalar-only (existing 5.4.2 caps: type ≤ 50, ≤ 20 keys, key ≤ 50, value ≤ 200).

Do not store lists or nested objects in `known`. Put them in `conversation_state`, sent and returned beside `known_context`:

```
conversation_state = {
  last_product_ids: [101, 205, 310],
  pending_action: "confirm_kitchen_cart" | "show_alternatives" | "add_named_products" | "ask_next_kitchen_term" | null,
  pending_question: "هل تريد إضافة هذه المنتجات إلى السلة؟",
  cart_plan: {
    added_term_ids: [12, 18],
    added_product_ids: [101, 205],
    pending_term: "أواني المطبخ",
    failed_term_ids: []
  }
}
```

Sanitization for `conversation_state`:

- `last_product_ids` and `added_product_ids`: integers, max 30.
- `added_term_ids` and `failed_term_ids`: integers, max 30.
- `pending_action`: allow-list only.
- `pending_question`: string ≤ 200.
- `pending_term`: string ≤ 80.
- Reject unknown keys.

Frontend: persist `conversation_state` in sessionStorage next to `known_context`, send it on every POST `/chat`, and replace it from the response before saving history.

Server: return `conversation_state` on every chat path, including chitchat, ask, empty products, and cart failure.

## Smart نعم

`نعم` / `ايوه` / `موافق` never choose a path by themselves. They execute `pending_action`:

| Previous pending_action | نعم means |
|---|---|
| `confirm_kitchen_cart` | Add the planned kitchen products to the cart. |
| `ask_next_kitchen_term` | Add one product from that subcategory. |
| `show_alternatives` | Search again excluding `last_product_ids`. |
| `add_named_products` | Add the last shown product ids. |
| null | Ask one clarifying question. Do not replay the last catalog. |

If the previous assistant question was “هل تريد رؤية منتجات أخرى؟”, set `pending_action=show_alternatives` before waiting for نعم.

## Plan versus purchase

| Customer request | Behavior |
|---|---|
| خطط لي مشتريات المطبخ / تخطيط لشراء مستلزمات المطبخ / اقترح سلة للمطبخ | Build a plan, show one candidate per subcategory, do not add. Ask: هل أضيفها إلى السلة؟ `pending_action=confirm_kitchen_cart`. |
| اعمل سلة المشتريات / أضف هذه المنتجات | Add immediately. |
| أضف هذه المنتجات | Add the last shown ids only. |
| نعم | Run `pending_action` only. |

Planning must not add to the cart without explicit build/add language or a matching pending confirmation.

## Data flow

```
message + history + known_context + conversation_state
        |
        v
local intent detect (no LLM)
        |
        +-- greeting --> keep all state, chitchat
        +-- switch_category --> reset type-known, clear name/brand filters that belong to the old type
        +-- refine --> merge scalar filters, search, rank, update last_product_ids
        +-- plan_cart --> pick candidates, do not add, set pending_action=confirm_kitchen_cart
        +-- build_cart --> Kitchen Cart Builder --> WC cart add --> totals --> next missing term question
        +-- continue --> execute pending_action OR next required question
        |
        v
LLM only if type/intent still unknown
```

Scalar `known` additions:

- `brand`: canonical brand, e.g. `skechers`
- `recency`: `newest`
- `name_contains`: `رجالي`
- `skin_type`, `purpose`: already in templates; extract from free text

## Search and ranking

### Men’s products

- Clear previous `type` and type-specific known (skin_type, purpose, etc.).
- Set `name_contains=رجالي`. Do not assign `أزياء` or any other type slug.
- Search product titles across the catalog first.
- Category is fallback only if title matches are empty.

### Skin care

From “بشرتي دهنية وأعاني من حبوب”:

- `type = العناية_بالبشرة`
- `skin_type = دهنية`
- `purpose = علاج حبوب`

Rank name / short description / tags that match oily/acne terms ahead of generic or dry-skin items. Do not lead with a dry-skin moisturizer.

### Brand + newer model

- “سكيتشرز” stores `brand=skechers` (Arabic and Latin aliases).
- “موديل أحدث” sets `recency=newest` and keeps the brand.
- Recency sort: product date created, then product id as a tie-breaker. Do not use higher id alone as proof of a newer model.
- Then rating, then sales.
- Do not replace the brand set with a generic casual shoe.

### Kitchen cart picker

Pick one in-stock purchasable product per subcategory, in this order:

1. Matches the kitchen subcategory.
2. In stock and actually purchasable (simple, or a specific in-stock variation — never add a variable parent).
3. Not already in the WooCommerce cart and not in `cart_plan.added_product_ids`.
4. Within confirmed profile `budget_max` when present.
5. Highest rating with a minimum review count of 1; unrated products rank below rated ones.
6. Highest sales when ratings are within 0.3 of each other.

### Repeat suppression

Excluding `last_product_ids` must not stop the search.

- If alternatives exist, show them and set `pending_action=show_alternatives` only when the assistant explicitly offered more.
- If no alternatives exist, say so in one sentence and ask one new question. Do not invent products and do not reprint the same ids.

## Kitchen Cart Builder

Separate from search listing and from `SA_Project_Planner` / `SA_Project_Progress`.

Kitchen subcategories to resolve against live WooCommerce terms: مطبخ، أواني، تخزين، أجهزة مطبخ, plus matching store terms.

Build-cart steps:

1. Resolve subcategory term ids.
2. For each term not in `added_term_ids`, pick one candidate by the picker rules.
3. Add to the WooCommerce cart (variation id when required).
4. Record success in `added_term_ids` / `added_product_ids`.
5. On a single add failure, keep earlier successes, record the term in `failed_term_ids`, continue.
6. Reply with added lines, skipped/failed terms, and cart totals.
7. Ask one question about the next missing subcategory. Set `pending_action=ask_next_kitchen_term`.

Idempotency: the same POST body (same message + same `last_product_ids` + same `cart_plan`) must not add the same product twice. Check cart contents before `add_to_cart`.

Cart security:

- Require the existing REST nonce (`X-WP-Nonce`).
- Require a WooCommerce session/cart. If missing, do not claim success; return per-item add-to-cart URLs and keep the plan in `conversation_state`.
- Validate product id, purchasable, in stock, and (for variations) a chosen variation before add.
- Recalculate cart totals after successful adds.
- Add-to-cart URL is not treated as a successful add.

Edge cases:

- Empty subcategory: skip and continue.
- Unpurchasable candidate: try the next in the same subcategory.
- No kitchen terms: keyword fallback (مطبخ / أواني / تخزين) and label them as alternatives.
- Guest without session: URL fallback, no silent success.

## Project isolation

Kitchen cart work is independent of projects.

- Adding a kitchen item to the cart does not attach it to a kitchen project.
- Buying Skechers does not attach to a kitchen project.
- Viewing a product or adding it to the wishlist does not mark project progress.
- Project planner and progress APIs stay unchanged in 5.5.0.

## Error handling

- Rate limit and nonce behavior remain as in 5.4.2. Chat.js still refreshes nonce on 403.
- LLM failure: stay on local intent; never invent products.
- Partial cart add: keep successes, report failures, do not roll back.
- Invalid `conversation_state` from the client: ignore the bad fields and continue with empty defaults.

## Testing

Three levels. Existing 5.4.2 tests must still pass.

### Analyzer

- “مستلزمات رجالية” after skin care → `name_contains=رجالي`, type is not forced to skin care.
- “بشرتي دهنية وأعاني من حبوب” → `skin_type=دهنية`, `purpose=علاج حبوب`.
- “سكيتشرز” then “موديل أحدث” → brand kept, `recency=newest`.
- “تخطيط لشراء مستلزمات المطبخ” → `plan_cart`, not `build_cart`.
- “اعمل سلة المشتريات لمستلزمات المطبخ” → `build_cart`.
- “نعم” with `pending_action=confirm_kitchen_cart` → `build_cart`.
- “نعم” with `pending_action=show_alternatives` → search excluding last ids.
- “نعم” with null pending_action → clarifying question, no catalog replay.
- Greeting still keeps type and known without LLM.

### Search

- Oily/acne ranking does not lead with a dry-skin cream.
- Brand + recency keeps Skechers and sorts by date created then id.
- Name filter matches titles containing رجالي before category fallback.
- Repeat exclusion returns alternatives when they exist, empty+message when they do not.
- Variable parent is never a cart candidate; an in-stock variation may be.

### Controller

- Build cart adds purchasable items and returns totals.
- Plan cart does not call add_to_cart.
- Duplicate POST does not add the same product twice.
- Missing WooCommerce session returns add URLs and does not claim success.
- One failed add keeps prior successes and records `failed_term_ids`.
- Response always includes `conversation_state`.
- 5.4.2 nonce, greeting-keep-context, and local-type tests remain green.

Mocks are required; no live store in this environment.

## Files expected to change

- `services/class-conversation-analyzer.php`
- `services/class-smart-search.php`
- `core/class-chat-controller.php`
- `assets/js/chat.js`
- `config/question-templates.json`
- new helper for kitchen cart building (e.g. `services/class-kitchen-cart.php`)
- `tests/test-next-improvements.php` and a new 5.5 test file covering analyzer/search/controller
- `smart-advisor.php` version bump to 5.5.0
- changelog note for 5.5.0

Do not modify project planner/progress behavior.

## Implementation order

Do not merge a stage before it has its own tests.

| Stage | Work |
|---|---|
| 0 | Inventory current 5.4.2 behavior and tests. No live deploy. |
| 1 | Intent Engine |
| 2 | Conversation State Manager (REST + chat.js) |
| 3 | Smart Ranking |
| 4 | Kitchen Cart Builder (plan vs add) |
| 5 | WooCommerce add, nonce, idempotency, totals |
| 6 | Full analyzer/search/controller tests plus 5.4.2 regression |
| 7 | Staging only, with rollback via the previous plugin zip |

## Success criteria

- Category change no longer replays the previous category.
- “مستلزمات رجالية” searches product names for رجالي.
- Oily/acne query does not lead with dry-skin cream.
- Skechers + newer model stays on Skechers and uses date created.
- Planning kitchen supplies does not add to cart.
- “اعمل سلة المشتريات” adds one product per subcategory and asks one follow-up.
- نعم executes only `pending_action`.
- Projects remain isolated from the kitchen cart.
- Existing 5.4.2 tests still pass.
