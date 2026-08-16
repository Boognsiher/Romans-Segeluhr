# Diagnose-Logs (rohe CSV-Mitschnitte von Testfahrten)

Rohdaten aus `data/diagnostics/DiagnosticsLogger.kt` (siehe
`../Erweiterung_Diagnose_Log.md`), unverändert vom Handy exportiert. Hier
abgelegt, damit sie nach einem Törn nicht nur lokal beim Menschen liegen,
sondern für spätere Auswertungen/Vergleiche griffbereit sind.

Dateiname = Originalname vom Handy (`diagnose_<HHMMSS-Start>.csv`),
Datum steht nur im Namen der Session, nicht separat gepflegt.

## Bisherige Logs

- `diagnose_20260815_140808.csv` — 15.08.2026, erster echter Segeltörn.
  Auswertung: Bug in `WindEngine.abortCalibration()` gefunden+gefixt
  (irreführendes "Kalibrierung abgebrochen."-Banner), siehe Commit-
  Historie und `PROJEKT_STATUS.md`.
- `diagnose_20260816_103742.csv` — 16.08.2026, zweiter Wassertest, noch
  mit der alten App-Version (vor obigem Fix) aufgezeichnet.
