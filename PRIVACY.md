# Informativa sulla privacy di Ponte

_Ultimo aggiornamento: 28 ottobre 2026_

Ponte collega due dispositivi Android della stessa rete locale: il telefono che fa da sorgente
(ad esempio un Galaxy con Samsung DeX) e il dispositivo su cui viene mostrato il desktop.

**Dati raccolti: nessuno.** Ponte non ha account, non usa servizi di analisi o pubblicità, non
contatta server su internet e non invia dati a nessuno, sviluppatore compreso.

**Cosa resta sul dispositivo**
- La chiave crittografica con cui Ponte si fa riconoscere dal Debug wireless del telefono collegato.
- Nome, modello e indirizzo di rete dei telefoni associati, e le impostazioni dell'app.
- Un registro tecnico temporaneo in memoria, che lasci il dispositivo solo se scegli tu di
  esportarlo e condividerlo.

**Cosa viaggia sulla rete locale:** l'immagine e l'audio del desktop, i tocchi e i tasti, il
contenuto degli appunti (per copiare e incollare tra i due dispositivi) e i file che scegli di inviare.
Questi dati passano solo tra i tuoi due dispositivi. Il canale di controllo ADB è cifrato (TLS);
il canale video "diretto", se attivo, non è cifrato ma è protetto da un codice monouso per sessione.
Puoi disattivarlo nelle impostazioni per far passare tutto dal canale cifrato.

**Permessi:** rete e Wi-Fi (per trovare e collegare il telefono), notifiche (per la notifica della
sessione attiva), servizio in primo piano (per mantenere il collegamento quando l'app non è visibile).

Per domande: apri una segnalazione su https://github.com/peppedess/Ponte/issues
