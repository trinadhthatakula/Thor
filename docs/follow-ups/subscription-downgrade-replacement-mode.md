# Changing to a cheaper support tier uses an upgrade-shaped replacement mode

**Tier 3** — filed 2026-08-05, decision open. Touches real money, so it is the owner's call, not a
cleanup.

## What the code does

`BillingProcessorImpl.launchBillingFlow` sets one replacement mode for every plan change, in every
direction:

```kotlin
SubscriptionProductReplacementParams.newBuilder()
    .setOldProductId(oldProductId)
    .setReplacementMode(SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE)
```

Nothing compares the old tier's price to the new one. A subscriber tapping a **cheaper** tier sends
the same parameters as one tapping a dearer tier.

## Why it is being filed now rather than fixed

It is **not** a regression from the dynamic-catalogue work (#351), and that was verified rather than
assumed. Before that change the app queried four hardcoded tiers — `support_tier_5`, `_10`, `_25`,
`_50` (`git show dev:…/BillingProcessorImpl.kt`, the `queryProducts` list). A `support_tier_50`
subscriber could already tap `support_tier_5`. The downgrade path is as old as having more than one
tier; #351 only widens which pairs are reachable, by putting `support_tier_1`, `_2` and `_3` below
the old floor of 5.

An adversarial review of #351 raised this and it was refuted **as a finding against that diff** —
correctly, since the diff does not touch any line the failure runs through. Refuted-as-a-regression
is not the same as fine, which is why it is here.

## What is verified, and what is not

**Verified locally** (`javap` on `billing-9.1.0.aar`): the mode set is
`UNKNOWN_REPLACEMENT_MODE=0`, `WITH_TIME_PRORATION=1`, `CHARGE_PRORATED_PRICE=2`,
`WITHOUT_PRORATION=3`, `CHARGE_FULL_PRICE=4`, `DEFERRED=5`, `KEEP_EXISTING=6`. Thor hardcodes 2.

**Not verified here:** that Play *rejects* `CHARGE_PRORATED_PRICE` for a downgrade. Google documents
that mode as applying to upgrades, but the `.aar` carries no doc text and the bytecode cannot say
what the Play Store server does with the request. **Do not treat "the tap fails" as established** —
it is the documented reading, untested on a device. It may equally succeed with a proration the
subscriber did not expect, which would be the worse outcome of the two.

## Deciding it needs a device, not a reading

The question is a product one before it is technical: what *should* a downgrade do? `DEFERRED`
(keep the current tier until it expires, then bill the cheaper one) is the conventional answer for
a voluntary downgrade and charges nobody a surprise. `WITH_TIME_PRORATION` switches immediately and
credits the remainder. Both are defensible; the current code picks neither on purpose.

Reproducing needs a real subscription on a test account: subscribe at a dearer tier, tap a cheaper
one, and record whether Play errors, and with what. A licence-tester account can do this without
real charges.

## If it is fixed

Pick the mode from the direction of the change, which the catalogue can now answer without a guess:
`BillingProduct.priceAmountMicros` carries Play's own number for exactly this kind of comparison
(it exists for [`sortSupportTiers`], and is never displayed). Compare only within one
`priceCurrencyCode` — across currencies the raw micros are not comparable, which is why the sort
keys on currency first.
