# Integrazione AirPods completa — review I2 consolidata

Fase IMPLEMENTAZIONE, giro CORRETTIVO2, I2 stabile. Manifest `a572b80b04a54a37beb333ac57638a03c6e485318457a97f0258ea6edd6e39b3`.

Sei reviewer indipendenti effettivi: tutti GPT-6.1 Sol, reasoning high. A protocollo/catalogo; B lifecycle/piattaforma; C media; D UI; E evidenze/APK; F miglioramenti approvati S1. Report individuali conservati senza riscriverne i voti. NV/NA, voti mancanti e provvisori non entrano in media o minimo; il voto I6 di F è esplicitamente parziale/provvisorio e viene escluso. I contributi riflettono i perimetri dichiarati dagli autori; una media non amplia la copertura o cancella un difetto.

| Criterio | A | B | C | D | E | F | Media (contributi) | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---:|---:|---|---:|---|
| I1 Completezza | 7 | NV | NV | NV | NV | NV | 7 (1/6) | 7 | Correggere Tile; chiudere la tracciatura residua |
| I2 Interfaccia | NV | NV | NV | 7 | NV | NV | 7 (1/6) | 7 | Correggere host modale senza login |
| I3 Lifecycle | NV | 8 | NV | NV | NV | NV | 8 (1/6) | 8 | Software nel perimetro verificato; OEM NV |
| I4 Protocollo | 8 | NV | NV | NV | NV | NV | 8 (1/6) | 8 | Software nel perimetro verificato; hardware NV |
| I5 Automazioni | NV | NV | 7 | NV | NV | NV | 7 (1/6) | 7 | Correggere indipendenza ear/CA |
| I6 Evidenze/APK | 8 | 8 | 8 | 8 | 9 | 8 prov. | 8.20 (5/6) | 8 | Verifiche software adeguate; limiti runtime dichiarati |
| I7 Confini/provenienza | 8 | 8 | NV | NV | 8 | NV | 8 (3/6) | 8 | Nessun difetto confermato nel perimetro |
| I8 Integrazione | NV | 8 | 7 | 6 | NV | NV | 7 (3/6) | 6 | Correggere host modale e ownership CA |
| S1 Ricerca/playlist | NV | NV | NV | NV | NV | 8 | 8 (1/6) | 8 | Soddisfatto nel perimetro software verificato |
| H1 Gate fisico | NV | NV | NV | NV | NV | NV | — | — | Aperto; nessuna prova fisica del candidato |

**Decisione: I2 non approvato.** Tre difetti confermati nel perimetro software richiedono il terzo correttivo I3 della nuova integrazione. È il terzo dei tre giri ordinari dopo l'iniziale; nessun reset dei contatori storici o del limite. Nessuna abilitazione prodotto deriva dai voti positivi. Product FALSE, H1/G1 NV.

- **FI2-01 / A1, media:** Tile non riconcilia RX/timeout/revoca/disconnessione mentre resta listening. Evidenza statica nel sorgente congelato, confermata dalla lettura root; nessuna riproduzione Android dichiarata. Serve osservazione limitata al listening, selector comune, cancellazione su stop/destroy e regressioni sui cambi di disponibilità senza acquisizioni.
- **FI2-02 / C-01, media:** UNKNOWN ear cancella una pausa esclusivamente CA accodata o la sua ownership. Il reviewer ha riprodotto con core/coordinatore reali manifestati e provider fake; root ha letto il ramo corrispondente. Serve invalidazione consapevole dei motivi, con regressioni integrate locali/C6 e overlap senza estendere auto-resume.
- **FI2-03 / D-01, alta:** l'onboarding disegna una pagina opaca dopo l'host modale globale. Dimentica e menu possono essere coperti anche con prodotto FALSE. Deduzione dalla gerarchia produttiva, non riproduzione Compose/device. Serve correggere ownership/ordine dello strato e provare interazione, occlusione e priorità Back nella gerarchia equivalente reale.

**Tracciatura residua aperta:** sei chiavi single/double/triple stem hanno consumer/default upstream, senza editor attivo individuato. Non sono dichiarate automaticamente feature mancanti o escluse. Serve una classificazione basata anche su writer dinamici e contratti esportati, preservando le35 famiglie richieste e senza creare nuove impostazioni da un semplice getter.

Gli8 test mirati e686 estesi, tre build e24 controlli r3 sono esecuzioni parent sovrapposte e circoscritte. E ha ripetuto personalmente i24 controlli strutturali; B ha37 asserzioni host; C ha il probe media integrato. Non sommare esecuzioni sovrapposte o confonderle con hardware. Tutti hanno riconfermato integrità dei2679 file e dell'input privato. I912 input registrati ma non inclusi nel pacchetto scoped non sono ricertificati dai soli444 presenti. Full lint, linkage completo, diagnostici reali JNI, full-host Samsung, decoder e hardware rimangono limitati/NV come nei report.

Il preflight telefonico root successivo al freeze ha letto solo build/package/firma: conferma compatibilità del signer con l'installazione, non un install o test AirPods. Il congelato resta immutato. Dopo correzioni e nuova validazione/review restano tre sessioni standalone, eventuali fix fisici, nuovo APK abilitato e relativo collaudo prima della consegna.

