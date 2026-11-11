# Review full integration I0 — riepilogo orchestratore

Fase IMPLEMENTAZIONE, giro INIZIALE, I0-stable. Reviewer A/B/C/D: GPT-6.1 Sol high, tutti indipendenti e terminali. Manifest1130 file SHA25677cfdf2a2e9561a0a4b91802217e77b010a2bcb85c5b87dd8c24b5abf3b659bb. Report individuali nei file review-I0-A/B/C/D. Nessun voto dell'orchestratore.

| Criterio | A | B | C | D | Media (n/4) | Minimo | Esito |
|---|---:|---:|---:|---:|---:|---:|---|
| I1 | 7 | NV | 7 | NV | 7.00 (2/4) | 7 | Completezza non approvata; difetti CA/BG |
| I2 | 8 | 8 | NV | 7 | 7.67 (3/4) | 7 | Correggere D2/D3 |
| I3 | 7 | 6 | NV | 8 | 7.00 (3/4) | 6 | Correggere B1/B2 e BG |
| I4 | 8 | 8 | NV | 8 | 8.00 (3/4) | 8 | Campione software; fisico NV |
| I5 | 6 | 8 | 6 | 6 | 6.50 (4/4) | 6 | Correggere BG, volume e prima CA |
| I6 | 8 | 8 | 7 | 9 | 8.00 (4/4) | 7 | Regressioni integrate mancanti |
| I7 | 8 | 8 | 8 | 8 | 8.00 (4/4) | 8 | Scope verificato; NV dichiarati |
| I8 | 8 | 7 | 7 | 8 | 7.50 (4/4) | 7 | Correzioni trasversali necessarie |
| H1 | NV | NV | NV | NV | — | — | Gate obbligatorio aperto |

Media e minimo escludono NV; il numero di contributori è esplicito. Un voto numerico parziale non diventa certificazione esaustiva: leggere i limiti dei report. H1 non è convertito in zero. L'indisponibilità del telefono non è un difetto software.

**Decisione: correggere.** La soglia ≥8 per ogni criterio numerico valutato e l'assenza di difetti gravi non sono soddisfatte. Nessuna maggioranza o media compensa i difetti riproducibili. Primo giro correttivo della nuova implementazione; massimo tre dopo l'iniziale, senza reset dello storico.

| ID consolidato | Origine | Gravità | Problema e prova richiesta |
|---|---|---|---|
| FI0-01 | A1 = D1 | Alta | Automazioni esterne ignorano background OFF. Matrice locale/esterno × visibilità × consenso e cancellazione in coda/correlazioni. |
| FI0-02 | C-R1 | Alta | Ricevuta persistente precedente cancella il passo successivo e il restore CA. Integrare coda/provider con almeno due passi, restore, locale/C6 e override negativi. |
| FI0-03 | B1 | Alta | Proxy A2DP tardivo non rivaluta l'ingresso companion. Disponibilità distinta dal disconnesso; un solo rientro dello stesso ingresso attraverso C3/C4, con stop/revoca/stale negativi. |
| FI0-04 | B2 | Media | Unpair Android non passa dal cleanup UNPAIRED e disassociazione propria. Test controller con/senza ticket e nessuna rimozione di associazioni estranee. |
| FI0-05 | C-R2 | Media | Primo attivo CA live ignorato come baseline. Distinguere cache/live, duplicati, configure e recupero target senza replay sintetico. |
| FI0-06 | D2 | Media | Consenso ON non revocabile senza prerequisiti. Distinguere abilitazione da spegnimento selezionato; vero dispatcher dopo NLS/media/BT revocati. |
| FI0-07 | D3 | Bassa | Setup abilitato mostra indisponibilità generica. Nota neutra/assente quando abilitato e motivo concreto solo se disabilitato. |

Riconfermati staticamente i rami corrispondenti nel congelato. C ha inoltre riprodotto FI0-02/FI0-05 con JShell sulle classi congelate; gli script restano in reviewer-scratch/focus-c e non sono prove radio. Nessun gate prodotto rimosso: conformance FALSE, H1 NV e G1 storico NV. Nessuna nuova prova telefono/radio/chiamata autorizzata.

