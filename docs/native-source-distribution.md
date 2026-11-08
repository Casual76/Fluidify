# Fluidify 2.1.0-beta.1 native sources and notices

This is a source and notice supplement to app source commit `22ac7bc05ee043d09c920dd784794accc671a7e3`, with published Engine gitlink `bcfc73debe9d4cb78942cc6cdf177aacc4fa7dcb`. It changes no functional source, build script or API. The license/metadata supplement is a separate descendant commit; the manifest names the app source commit being supplemented, avoiding a self-referential commit hash.

## Verbatim librespot MIT text

The three local vendor crates are librespot-core, librespot-connect and librespot-playback 0.8.0. Their `.cargo_vcs_info.json` names upstream base `d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d`. At that exact ref, each crate Cargo.toml inherits the workspace license, and the workspace declares MIT.

The [primary license](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/LICENSE), copyright **2015 Paul Lietar**, is copied verbatim to each vendor crate's LICENSE and LICENSES/Librespot-MIT.txt. SHA256: `b6e979ad38c429c5811e9eb6930040a984619786f55ebc60660db05df5325ed6`; upstream Git blob `b06ecfb231a3959337f79df3ad613551cefbc36e` independently verified against downloaded bytes. URLs, exact ref, crate/workspace manifest hashes and destinations are in LICENSES/Librespot-MIT.source.json. No copyright text was invented; existing local patches and their notices remain unchanged.

## Source attachment

Attachment: `fluidify-2.1.0-beta.1-native-sources.zip` (14,757,106 bytes).
SHA256: `8f843905beabe9d8166c0322ec6c4d48cfe0565454f472de02a15f3717d99e97`.

The public inventory and attachment metadata are in `docs/source-distribution/2.1.0-beta.1-native-sources.json`. The ZIP contains **1571** previously inventoried raw source/header/license-like files plus **five** Eigen COPYING notices omitted by the previous filename filter and the verbatim librespot MIT text: **1577 payload files**, plus SOURCE-MANIFEST.json and README.txt, **1579 ZIP entries**. The attachment is delivered separately; a filename in this document does not establish that it has been uploaded or made available to recipients.

Pinned source refs:

| Component | Repository | Exact ref | Notice location |
|---|---|---|---|
| Bungee v2.4.24 | https://github.com/bungee-audio-stretch/bungee | 746833f68a574d997ec50443e7cfd2d37b026302 | native-dsp/bungee/LICENSE, MPL-2.0 |
| Eigen | https://gitlab.com/libeigen/eigen | c29c800126982c561e8d0b9255dc65474cd98de3 | submodules/eigen/COPYING.* and file headers |
| PFFFT | https://bitbucket.org/jpommier/pffft | 02fe7715a5bf8bfd914681c53429600f94e0f536 | submodules/pffft/pffft.c and fftpack.c notices |
| cxxopts | https://github.com/jarro2783/cxxopts | 4bf61f08697b110d9e3991864650a405b3dd515d | submodules/cxxopts/LICENSE; upstream CLI is not compiled by the app target |

App CMake compiles Bungee src/*.cpp and PFFFT pffft.c/fftpack.c, and includes Eigen. This attachment is the finite inventoried app-oriented source selection, not the entire upstream repositories or a standalone upstream CLI/test build kit. Included Eigen test source files may depend on upstream test/build helpers outside this selection. App Rust source/vendor and CMake wrappers remain in the referenced app commit; they are not replaced by this Bungee attachment. No full build from this package has been demonstrated.

## Verify and reconstruct

1. Verify the external ZIP SHA256 and size above before opening it. The ZIP SHA is external to avoid recursive self-hashing.
2. Reject duplicate names, absolute/drive paths, traversal components and symlinks. Entries are relative native-dsp/... or notice/metadata files, Unix regular-file mode 0100644, with no executable permission bits. No .git, build, target, cache, signing keys or generated binary entries are included.
3. Read SOURCE-MANIFEST.json without extracting, and compare the raw SHA256 of each of its 1577 payload entries. The external public manifest also pins the embedded manifest and README hashes. ZIP CRC and all hashes were verified after packaging. No newline conversion is permitted.
4. Extract into a new empty staging directory. Obtain the app source at the exact appSourceCommit and the Engine at the exact gitlink. Source files under native-dsp/bungee can be placed alongside that app checkout. These are source placement instructions; no build is run or claimed by verification.
5. To reconstruct the same archive from extracted bytes, include exactly those payload entries, SOURCE-MANIFEST.json and README.txt, ordered lexicographically by ASCII path. Use ZIP_STORED (no compression), fixed timestamp 1980-01-01 00:00:00, Unix regular-file mode 0100644, implicit directories, no comments or extra fields. Packaging was performed twice and compared byte for byte. This proves archive reconstruction from these bytes, not reproducibility of a compiled library.

## Binary provenance and remaining limits

The three Rust release outputs in the configured Cargo target cache match the three approved I4 libsquarecore.so input hashes. Their depfiles cite current source/dependency paths, including owned_load.rs; Cargo fingerprint metadata also exists. These are useful build-cache records, but do not contain a complete historical SHA256 fingerprint tying every source byte to each output. I4 reused cargoBuild as UP-TO-DATE. The absent native/target folder is irrelevant to whether historical builds exist: the configured cache exists elsewhere and was inspected read-only. No cache was deleted or compiler invoked.

Bungee and its local submodules were clean at the pinned refs; the source payload hashes were verified against the prior inventory. This does not establish complete historical source-to-packaged-ELF equivalence. No binary rebuild, section equivalence, runtime acceptance, universal license completeness or legal compliance is certified. ROOT retains publication ownership, source/notice delivery checks, any wider dependency audit and any separately authorized rebuild. Stable 2.0.1, Product FALSE and H1/runtime NV remain unchanged.
