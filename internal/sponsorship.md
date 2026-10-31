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

The owner's October 31 implementation request authorizes reusing that existing
profile for MeridianDB's repository button and README link. Its current central
profile description concerns the education portfolio. This scoped change leaves
central profile copy, tiers, payment details and other repositories as they are;
it creates no new sponsorship account or financial transaction.

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

README changes trigger documentation checks, but README/FUNDING/internal-only
changes do not trigger the configured Pages publisher. No site deployment is
needed for the repository's Sponsor button. Future website support links require
their own public-documentation and publication checks.

Primary configuration reference checked October 31, 2026:
[GitHub sponsor-button documentation](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository).
