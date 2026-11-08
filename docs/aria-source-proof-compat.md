# Fluidify / PampAI Aria source-proof compatibility

API freeze candidate: `aria.source-proof` version **1**, transported only through
existing negotiated bridge **v2 / typed_results**. Legacy catalog protocol stays
**1**. No API, consent, version, manifest, signing or Engine changes in this increment.

The imported baseline registers `source_capabilities`, `source_lease`,
`source_validate`, `source_read` in `fluidifyRegistry`; `FluidifyToolHost.registry()`
returns that wrapped registry. PampAI prefixes remote names with `fluidify_`.
Legacy direct reads do not produce source proof. Text is never authorization.

The frozen implementation keeps signed caller identity, explicit account, history
and Web API generations, recording state, per-process incarnation, monotonic 60s
capability lifetime, nonrenewing leases, exact filters/limit and hardcoded read
allowlist. Authorization UUIDs are response-only, stable for the same caller and
observed source state across capability renewal; changed state clears handles and
identities. Capacity rejects new callers rather than evicting stable identities.
Cancellation propagates; reads validate before dispatch and after production.
Fragments must originate under the same lease; validation uses the original scope.
Original fragment hashes, offsets, cursors and expiry remain enforced by the base
tools. Mutations still require their existing confirmation and operation identity.

This is a source-level freeze candidate, pending integrated validation. In Fluidify,
readiness is `HistorySession.access != null && recording`; it is not an independently
checked auth `READY` enum. The source adapter consumes existing generation signals.
Root must confirm that sign-out, same-account reconnect and deletion publish those
signals in the integrated app. No credential value or credential-derived hash is
needed by this contract. Remote revocation not yet observed by the source remains
undetectable. Source validation is a point-in-time check, not a distributed lock;
Aria must retain its local revoke/forget fence and revalidate before consumption.

Run host checks from this worktree (no SDK, compiler or device):

```powershell
python -B -m unittest discover -s tests -p test_source_proof_artifact.py -v
python -B tests/check_source_proof_baseline.py 'C:/VibeCoded Projects/Fluidify/build/aria-source-proof-compat/input-snapshot-v1' --output build/aria-source-proof-compat/baseline-evidence.json
python -B tools/check-source-proof-artifact.py '<immutable-unminified-apk>'
```

The artifact checker resolves DEX **class_defs**, not string occurrences or mere
type references. It checks 14 selected production definitions across multidex,
including provider, registry, proof state and bridge protocol. Exit 0 means selected
definitions exist; exit 1 means missing definitions; exit 2 means unreadable or
unsupported input. R8 renamed/inlined output needs a separate mapping-aware review;
do not treat failure as permission to disable shrinking or change keep rules.
It does not verify provider registration in the packaged manifest, method bodies,
runtime catalogs, typed negotiation, signatures or authorization effects.

Evidence and integration handoff are in this worktree's
`build/aria-source-proof-compat/REPORT.md`, `HANDOFF.md`, `baseline-evidence.json`,
`packaged-evidence.json`, `host-tests.log`, `delivery-manifest.json` and patches.
That build directory is ignored by Git; the durable scripts and this document are
ordinary source files. The handoff manifest inventories ignored evidence explicitly.
