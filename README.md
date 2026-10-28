# Ponte

Samsung DeX wireless dal Galaxy (One UI 8+) su qualsiasi telefono o tablet Android, senza PC e senza root.

Ponte si collega al Galaxy tramite **Debug wireless**, avvia [scrcpy-server](https://github.com/Genymobile/scrcpy)
su un display virtuale (dove One UI 8 fa partire DeX da solo) e mostra il desktop a schermo intero,
inoltrando tocchi, mouse, tastiera, audio e appunti.

## Uso
1. Sul Galaxy: Opzioni sviluppatore → **Debug wireless** attivo (stessa rete Wi‑Fi).
2. In Ponte: **Associa il Galaxy** → sul Galaxy *Associa dispositivo con codice* → inserisci il codice.
3. Da lì in poi basta **Avvia DeX**.

Linguetta in alto durante la sessione: Indietro, Home, App recenti, App, Esci.

## Telefoni non Samsung
Ponte funziona con qualsiasi Android 10+ con Debug wireless: al posto di DeX compare ciò che il sistema
mostra sui display secondari (modalità desktop di Android, un launcher, oppure uno schermo vuoto).
- **Prepara la modalità desktop** attiva finestre libere e desktop sui display esterni (poi va riavviato il telefono).
- Il pulsante **App** della linguetta apre qualsiasi app del telefono sul display virtuale.

## Componenti di terze parti
- `app/src/main/assets/scrcpy-server.jar`: scrcpy-server v5.0.1 © Genymobile / Romain Vimont, Apache License 2.0
  (SHA‑256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`).
- [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache 2.0 / GPL‑3.0), Conscrypt, BouncyCastle.

"Samsung", "Galaxy" e "DeX" sono marchi di Samsung Electronics. Ponte non è affiliato a Samsung.
