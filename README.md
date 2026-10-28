# Ponte

Il desktop del telefono su un altro schermo Android, via Wi-Fi: **Samsung DeX** dai Galaxy con
One UI 8+, la **modalità desktop di Android** dagli altri telefoni. Senza cavi, PC o root.

## Come funziona
1. Ponte si collega al telefono sorgente con il **Debug wireless** (client ADB integrato:
   [libadb-android](https://github.com/MuntashirAkon/libadb-android), associazione SPAKE2 + TLS).
2. Copia sul telefono [scrcpy-server](https://github.com/Genymobile/scrcpy) 5.0.1 e lo avvia con
   un **display virtuale** della dimensione di questo schermo: One UI 8 ci avvia DeX da solo.
3. Avvia anche `:relay`, un piccolo programma (app_process) che espone i socket di scrcpy su una
   porta TCP con un codice monouso: il video viaggia su un **canale diretto** invece che dentro ADB.
   Se la rete lo impedisce, Ponte torna da solo ai flussi ADB.
4. Il video (H.264/H.265) è decodificato in hardware con MediaCodec, l'audio è AAC; tocchi, mouse,
   tastiera e appunti tornano indietro con il protocollo di controllo di scrcpy.

## Funzioni
- Configurazione guidata, più telefoni associati, riconnessione automatica
- Servizio in primo piano: il desktop resta collegato quando esci dall'app
- Barra comandi flottante; tocco diretto o touchpad; tastiera locale o del telefono
- Riquadro rapido, widget, scorciatoia; "Condividi → Invia al telefono"
- Statistiche (fps, Mbit/s, canale), registro diagnostico esportabile

## Struttura
- `app/` – l'app (Kotlin, Jetpack Compose, Material 3 Expressive)
  - `adb/` connessione, associazione, scoperta mDNS, `sync:` per l'invio dei file
  - `scrcpy/` sessione, protocollo, input
  - `session/` gestione della sessione e servizio in primo piano
  - `ui/` schermate
- `relay/` – ponte TCP che gira sul telefono collegato
- Test: `app/src/test` (protocollo byte per byte + screenshot Roborazzi). La CI pubblica APK,
  note di rilascio e il ramo `rapporto` con registri e screenshot.

## Licenze
- `app/src/main/assets/scrcpy-server.jar`: scrcpy-server v5.0.1 © Genymobile / Romain Vimont,
  Apache License 2.0 (SHA‑256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`).
- libadb-android (Apache 2.0 / GPL‑3.0), spake2-java, Conscrypt (Apache 2.0), Bouncy Castle (MIT).

Samsung, Galaxy e DeX sono marchi di Samsung Electronics. Ponte non è affiliato a Samsung.
Informativa privacy: [PRIVACY.md](PRIVACY.md).
