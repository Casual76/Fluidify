# Aria source proof v1

The signed bridge source registry now adds source_capabilities, source_lease,
source_validate and source_read. Consumers must negotiate existing typed v2 and use
source_read for evidence with a sourceProof receipt; direct legacy reads are not proof.

The shared exact API, schemas, allowlists, continuation rules, limitations and compiler
handoff are documented in C:/VibeCoded Projects/PampAI/docs/aria-source-proof-contract.md.
Proof code remains app-owned; pinned engine 3.0.5/a055257 is untouched.

Capabilities and leases bind exact caller, account/student, logical arguments, source
process and source authorization state. Validation runs before and after data production.
Fluidify also binds fragment snapshots to their producing lease. CV additionally binds
the in-memory atomic auth publication identity because dataEpoch alone can remain unchanged
on same-account reauthentication. No credential field is read or exported by proof tools.

The response-only authorization UUID appears in every capability and receipt. It remains
stable for the same caller and unchanged source state across capability/lease expiry and
replacement; it changes with source state/publication/generation or process restart.
It carries no standalone permission and cannot be used as a capability or lease. No credential
hashes are involved. The map retains at most 128 caller identities for the current state;
at capacity a new caller is rejected instead of evicting a renewable unchanged identity.
State transitions clear this map and invalidate outstanding handles. Handle TTL remains strict.

Source-local validation is point-in-time, with monotonic expiry at 60 seconds from capability
issuance. It does not detect unobserved upstream server revocation or supply a distributed
lock. Local Aria atomic revoke/forget fences remain independently required. Fail closed
when source validation is unavailable; never treat legacy text or a missing proof as valid.

Validation completed after ROOT's explicit compiler transfer: all 55 assigned cases pass
(27 Fluidify, 28 CV), alongside relevant source suites. CV assemble/app and assistant lint
pass. Fluidify's first app compilation/test run passes; later assemble and app lint are
blocked by unrelated AirPods compile errors, and core lint retains three existing unsafe
opt-in errors. No scoped code fixes or suppressions were needed. No native build ran; seven
existing Fluidify native outputs remain byte-identical. Full commands, suite counts, current
versus reused results and archived evidence are in the cross-app contract above and local
docs/validation-source-proof/. Runtime and next-phase acceptance remain NV.

All owned Gradle invocations ended. Compiler runner is explicitly released back to ROOT;
see validation-source-proof/runner-release.json. Any new runner use needs ROOT assignment.
