# Repository sponsorship

`.github/FUNDING.yml` owns the repository's funding recipient. The README's
Support MeridianDB section links to the same GitHub Sponsors profile, identifying
Tokenizer Inc. and describing voluntary support for development, tests and docs.
Keep the project's experimental status explicit. Add specific delivery/support
commitments only when separately agreed; the support link creates none.

## Verified recipient

Checked October 31, 2026: `https://github.com/sponsors/MasteryEducation` displays
**Mastery Education by Tokenizer Inc.**, Toronto, and identifies Fuad Efendi as
founder. It offers monthly and one-time support with a custom amount. This is the
existing GitHub recipient recovered from the earlier SoftwarePatternsLexicon.com
funding configuration at commit `b45e8312b13388f28957491e9ab2d15529266f86`.
The personal `https://github.com/sponsors/FuadEfendi` URL instead redirects to the
ordinary profile, so it is not used as a sponsorship destination.

The initial October 31 repository-button task reused that existing profile for
MeridianDB and left central account copy/tiers/payouts unchanged. The subsequent
profile-copy request is tracked separately; its exact short bio/introduction and
publication status live in [the profile source](sponsorship-profile.md). Preserve
account identity, recipient, tiers and payment settings during a copy-only update;
no new sponsorship account or financial transaction is involved.

## Maintain and verify

1. Confirm the intended profile remains an active Sponsors page, with the correct
   recipient identity. Preserve a known destination; verify any requested change.
2. Keep FUNDING YAML limited to actual configured funding channels. Use a GitHub
   account name, not a URL, for the `github` entry. Keep the README destination
   consistent; do not copy unused funding-platform placeholders.
3. Parse the YAML and check the public documentation with
   `.venv/bin/python scripts/build_docs.py`. No Maven work is needed for a funding
   link change. Inspect task-owned diff, commit and push under the scoped request.
4. Verify the live repository shows Sponsor and that its funding panel resolves
   to the expected recipient. Adding a file alone is not readback evidence.
   If the repository's Sponsorships feature is disabled, enable that specific
   feature under repository Settings → General → Features, then verify again.
   GitHub GraphQL also exposes `Repository.hasSponsorshipsEnabled` and
   `fundingLinks`. When the connector lacks that setting, authenticated `gh api
   graphql` can read the repository ID, set only `UpdateRepositoryInput`'s
   `hasSponsorshipsEnabled: true`, then independently requery the flag/recipient.
   Use the freshly read repository ID and preserve every other setting.

README changes trigger documentation checks, but README/FUNDING/internal-only
changes do not trigger the configured Pages publisher. No site deployment is
needed for the repository's Sponsor button. Future website support links require
their own public-documentation and publication checks.

Primary configuration reference checked October 31, 2026:
[GitHub sponsor-button documentation](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository).
The [GraphQL repository reference](https://docs.github.com/en/graphql/reference/repos)
documents the feature flag and scoped update input.

## Central profile descriptions

For an explicitly requested description update, prepare and retain the exact short
bio and introduction in the profile source above. Verify current public fields
through SponsorsListing.shortDescription/fullDescription, and update only those
fields through the authenticated Sponsors dashboard's Profile details. The public
GraphQL schema checked October 31, 2026 supports reading these descriptions but
exposes no existing-listing update mutation; use the dashboard to update a listing.

If GitHub requires Confirm access, retain the dashboard tab and ask the owner to
complete their passkey/authenticator flow. Keep publication pending until it saves
successfully. After saving, independently read both fields via API and verify the
rendered public introduction and links, then record the exact published copy/status.

Primary editing procedure checked October 31, 2026:
[GitHub Sponsors profile details](https://docs.github.com/en/sponsors/receiving-sponsorships-through-github-sponsors/editing-your-profile-details-for-github-sponsors).
