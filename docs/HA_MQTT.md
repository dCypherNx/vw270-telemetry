# Home Assistant / MQTT

Default prefix: `car/vw270`.

Examples include `car/vw270/car/speed_display_mps`, `car/vw270/car/odometer_m`, `car/vw270/car/fuel_percent`, `car/vw270/aa/connection_type`, `car/vw270/gnss/satellites`, `car/vw270/event` and `car/vw270/availability`.

Each state topic contains the complete JSON envelope, not just the scalar value. Scalar car/system/AA/GNSS/fused keys get retained MQTT Discovery config automatically.

High-frequency sensor arrays are intentionally not auto-discovered as hundreds of HA entities. Their raw samples remain in SQLite and can still be published under explicit MQTT topics.

## Availability

The MQTT client uses retained `car/vw270/availability`, with Last Will `offline` and `online` after a successful broker connection.

## Security

TLS uses the Android system trust store. The PoC stores the MQTT password in app-private `SharedPreferences`; a dedicated publish-only MQTT account for `car/vw270/#` is preferable.

## Sensores recomendados

Para automações do Home Assistant prefira `fused/speed_mps` e `fused/odometer_m`. Eles preservam o dado do carro quando disponível e mudam explicitamente para fallback GNSS quando necessário. Os tópicos `car/*` continuam sendo a evidência bruta do Android Auto para diagnóstico.
