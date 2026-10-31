# Repository sponsorship

`.github/FUNDING.yml` owns the repository's funding recipient. The README's
Support MeridianDB section links to the same project-specific GitHub Sponsors
profile and describes voluntary support for development, tests and docs.
Keep the project's experimental status explicit. Add specific delivery/support
commitments only when separately agreed; the support link creates none.

## Verified recipient

Current recipient: **MeridianDB**, at `https://github.com/sponsors/MeridianDB`.
On October 31, 2026, the owner completed the dedicated organization's enrollment
and authorized the remaining setup after approval. The authenticated dashboard
confirms its Sponsors profile is approved and public; independent GraphQL readback
identifies the same listing. Verify the actual public page and repository button
when delivering this migration. Public listing availability is distinct from
platform-managed payout verification; public-profile work changes no bank/tax data.

The project-specific profile identifies Fuad Efendi of Tokenizer Inc. as maintainer.
Exact short bio/introduction and publication evidence live in
[the profile source](sponsorship-profile.md). The organization uses the project's
existing PNG logo and links to `https://meridiandb.org/`.

The earlier MasteryEducation recipient and profile refresh are historical setup
choices, preserved in Git and [progress history](planning/progress.md). They are
not the current destination or an automatic fallback. Retiring that separate
organization/profile requires its own scope; this migration changes the MeridianDB
links and public identity only.

## Maintain and verify

1. Confirm the intended profile remains an active Sponsors page, with the correct
   recipient identity. For an approved migration, verify the new public listing
   before switching links, then reconcile FUNDING, README and current guidance.
   Retain old recipient/copy evidence in dated history rather than active defaults.
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

Feature the actual public MeridianDB/meridiandb repository when configuring this
project's Sponsors page. Use factual experimental-project copy; keep monetary
goals, tier prices/rewards, newsletters, bank/tax/payout changes and account removal
within separately requested scopes. Custom-amount support needs no invented tiers
or delivery commitments.

## Organization identity

For an authorized project-profile setup, use MeridianDB as the display name, the
current experimental-project description and `https://meridiandb.org/` as website.
Preserve unrelated profile fields and settings. Upload the prepared
`docs/assets/brand/meridiandb-github-1024.png` through organization Settings →
General → Upload new picture, review the crop and save it. Inspect the public
organization and Sponsors avatar after publishing; preparing a PNG alone is not
evidence of an account update. See [brand maintenance](brand.md) for the SVG master
and reproducible exports. Keep the exact published identity/copy receipt in progress
history, without credentials or private financial fields.

Primary editing procedure checked October 31, 2026:
[GitHub Sponsors profile details](https://docs.github.com/en/sponsors/receiving-sponsorships-through-github-sponsors/editing-your-profile-details-for-github-sponsors).
