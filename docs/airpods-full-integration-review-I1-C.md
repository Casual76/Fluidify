Reviewer **C — GPT-6.1 Sol, reasoning high**. Fase **IMPLEMENTAZIONE**, giro **CORRETTIVO1**, versione **I1 stabile**; nessun reset dei contatori.

**Esito: nessun difetto funzionale grave confermato nel percorso media esaminato; I6 richiede correzione del dossier.** Questa review non approva complessivamente I1–I8: i criteri non verificati integralmente restano NV. Conformance prodotto **FALSE**, H1 **NV**.

| Criterio | Voto 0–10 | Stato verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| I1 Completezza funzionale | NV | Ricognizione parziale | Letti piano, copertura e C1–C6; esaminati ingressi media/gesti. Non riconciliate indipendentemente tutte le 35 famiglie e 67 preferenze. | Completezza globale non certificata. |
| I2 Home/player/quick/full | NV | Statica parziale | Esaminati riferimento privato, gating UI, componenti Fluid e accesso AirPods signed-out in `SquareApp.kt:3069`. Nessuna verifica completa di geometria, temi e host reale. | QA visuale incompleta; nessuna penalità hardware. |
| I3 Lifecycle Android | NV | Statica parziale | Esaminati manifest, startup con gate FALSE e ammissione/soppressione in `AirPodsStartGate`. Non verificati integralmente tutti i rami Android/CDM. | Nessun voto dedotto dai soli risultati parent. |
| I4 Protocollo/stato | NV | Statica parziale | Riscontrati generazioni, pending 5 s e connect 10 s nel repository; non riesaminati integralmente codec e vettori pinned. | Conformità completa non certificata. |
| I5 Automazioni/media | **9** | Verificato host + statica provider | Test propri e lettura di coordinatore, core, provider locale/C6, route/focus/account e chiamate. Rampa, restore, ricevute persistenti, revoche e overlap coperti. | Nessun difetto grave confermato; runtime Android NV. |
| I6 Validazione/artefatti | **7** | Verificato con lacuna documentale | XML estesi, log build e artefatti controllati; verifiche host proprie PASS. Mancano evidenze espressamente dichiarate conservate. | **C-01, media**. |
| I7 Confini/provenienza | NV | Statica parziale | Esaminati permessi, attribuzione GPL/pinned e ingressi ordinari. Audit completo di asset, dipendenze e confini non eseguito. | Nessuna violazione confermata nel perimetro letto. |
| I8 Integrazione/preservazione | **8** | Verificato nel perimetro media | Wiring Application→runtime→core→provider; attach/detach player e teardown esaminati. Manifest I0: 135 file comuni core/Wear/Engine confrontati invariati; APK Wear invariato. | Preservazione complessiva e continuità decoder live non certificate. |
| H1 Gate fisico | NV | Non eseguito | Telefono indisponibile; nessuna operazione device, ADB, radio o installazione. | Gate prodotto obbligatorio ancora aperto. |

**C-01 — Evidenze dichiarate conservate, ma assenti dal congelato — gravità media.**

- **Trigger:** seguire i riferimenti di `evidence/I1-integration.md:13`, `:21` e `:35`.
- **Evidenza:** mancano `I1-media-integration-initial.log`, `I1-media-integration-recheck.log`, `I1-corrective-targeted.log` e `I1-targeted-pass`, anche dal manifest. Lo script `source/build/airpods-full-integration/orchestration/freeze-implementation-I1.py:69` e `:79` seleziona altri set/log e omette questi.
- **Conseguenza:** compilazione iniziale fallita, collisione riprodotta e run mirato da 27 restano dichiarazioni parent; non sono verificabili nei rispettivi output originali. Questo non invalida i test correnti passati.
- **Fix minimo:** aggiungere gli originali a un nuovo pacchetto immutabile manifestato, preservando I1 storico; oppure correggere esplicitamente la dichiarazione di disponibilità.
- **Regressione proporzionata:** controllo host che fallisca se un’evidenza obbligatoria referenziata manca dal pacchetto.

I controlli **eseguiti personalmente** comprendono **90 test JVM PASS**, compilando i sorgenti congelati di coordinatore/core/provider C6/storage e i test selezionati tramite Kotlin, senza Gradle o build Android. Gli iniziali 86 test sulle classi congelate sono inclusi nei 90, non aggiuntivi. [Log](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-C/source-junit.log>) e [runner riproducibile](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-C/run_review.py>).

La falsificazione ha coperto:

- `35→20`, restore `50`, seconda conversazione e fullstep `20→50`;
- ricevuta persistente precedente, ID iniziale già trattenuto, valore coincidente senza nuova ricevuta, ricevute incompatibili, ritardo e timeout;
- primo CA live attivo, duplicati, cache, reconfigure e recupero target senza replay automatico;
- matrice locale/esterno × visibile/background e consensi separati, compresi effetti accodati;
- overlap ear/CA nei due ordini, history C6, revoca e override senza PLAY/rollback.

Quattro test aggiunti nello scratch compongono **MediaRuntimeCore + coordinatore + provider C6 reale**. Il provider locale Android è stato esaminato staticamente; i suoi test Robolectric rimangono evidenza parent, non mia esecuzione. Il ramo chiamata ringing termina prima del percorso media (`AirPodsAndroidRuntime.kt:532–544`).

Ho verificato firme e ZIP 16 KB dei tre APK, 14/7 ELF64 debug/minified e presenza della classe JNI minificata. La verifica DEX non dimostra linkage completo o esecuzione; R8 con gate FALSE può eliminare percorsi. [Risultati artefatti](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-C/artifact-checks.json>).

**Parent archiviato:** riscontrati 67 XML con **586 test, zero failure/error/skip**; core 18 e Wear 150 restano storici, non rieseguiti. Nessun PASS lint o hardware attribuito.

Non ho consultato report, voti o comunicazioni degli altri reviewer, né memoria/rollout. Il contesto iniziale includeva riepiloghi storici generali AirPods, non usati come prova. Nessun `AGENTS.md` applicabile trovato.

**Integrità finale riconfermata:** 1166 file e screenshot esterno conformi, nessun file aggiunto/mancante nel congelato; manifest SHA256 `51627c5cb141cabccf85d95ca24f2430cf11bccbcd72c22d21e9bd0b5d198dac`. Scritture limitate allo scratch I1-C.
