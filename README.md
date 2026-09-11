# VW270 Telemetry

> **Compatibilidade Android Auto:** o serviço projetado usa a categoria `IOT`, portanto declara **Car API mínima 6**. A coleta de hardware continua condicionada ao que o host/OEM realmente publica.

PoC Android para extrair o **máximo de telemetria disponível sem root e sem OBD** de um VW Polo/VW270 usando Android Auto, APIs do telefone e uma camada diagnóstica opcional via Shizuku.

O projeto é propositalmente **read-only**: não envia comandos ao veículo, não usa CAN/OBD, não altera Google Play Services/Android Auto e não depende de código na head unit.

## Objetivo

Substituir a coleta pouco previsível dos sensores automotivos do Home Assistant Companion por um coletor dedicado, persistente e observável. O APK foi desenhado para responder empiricamente duas perguntas:

1. quais propriedades o host Android Auto do VW270 realmente entrega ao `CarHardwareManager`;
2. se o host cria a sessão do nosso `CarAppService` automaticamente ou se ainda exige que o app seja aberto uma vez na HU em cada conexão.

A segunda limitação é controlada pelo **host Android Auto**. Não existe API pública suportada que permita ao app do telefone fabricar um `CarContext`/`Session` por conta própria.

## Camada fused (continuidade)

O tópico `fused/*` é a saída operacional preferencial. `fused/speed_mps` usa a velocidade bruta do carro quando ela está chegando; após 2,5 s sem um valor válido, cai para a velocidade GNSS do telefone e marca o evento como `estimated`. `fused/odometer_m` ancora no último odômetro real recebido via Android Auto e, se o canal desaparecer, continua acumulando a distância GNSS. O status/atributos deixam claro se o valor é `measured` ou `estimated`; o valor estimado do odômetro é persistido entre reinicializações do processo.

## Fontes coletadas

### 1. Android Auto / veículo

Quando o host cria a sessão do `CarAppService`, `CarHardwareCollector` registra tudo o que a API pública projetada expõe:

- fabricante, modelo e ano;
- tipos de combustível e conectores EV;
- combustível/bateria, alerta de energia baixa e autonomia;
- velocidade bruta e velocidade exibida no painel;
- unidade de velocidade;
- odômetro e unidade de distância;
- estado de cartão de pedágio;
- estado da porta/conector de recarga EV;
- acelerômetro do veículo;
- giroscópio do veículo;
- bússola/orientação do veículo;
- localização fornecida pelo hardware do carro.

Os quatro sensores automotivos são solicitados com `UPDATE_RATE_FASTEST`.

Cada `CarValue` grava valor, status (`SUCCESS`, `UNIMPLEMENTED`, `UNAVAILABLE` ou `UNKNOWN`), timestamp original, `carZones` e timestamp de recepção no telefone.

### 2. Telefone

Durante uma projeção Android Auto, o serviço habilita automaticamente todos os sensores retornados por `SensorManager.TYPE_ALL`, Fused Location/GNSS, bateria/estado térmico/rede, Bluetooth, áudio, USB, Wi-Fi e inventário/versão do Android Auto. Fora do carro os coletores de alta frequência são desligados.

### 3. Estado do Android Auto

Sem depender do `CarAppService`:

- `CarConnection` informa `not_connected`, `native` ou `projection`;
- `UsageStatsManager` observa atividade do pacote `com.google.android.projection.gearhead`;
- `NotificationListenerService` registra apenas notificações publicadas pelo pacote do Android Auto.

### 4. Shizuku opcional, sem root

A PoC detecta o binder do Shizuku, versão, UID e estado da permissão. A primeira versão publicada mantém essa camada deliberadamente restrita a uma **sonda de capacidade**; não expõe um executor shell privilegiado genérico. Isso deixa preparado o caminho para probes Binder estreitos em uma evolução posterior sem tornar o APK um shell remoto.

Shizuku não é necessário para a telemetria normal.

## Persistência, logs e MQTT

Todos os eventos são gravados em `telemetry.db`. A política atual usa retenção de 7 dias e limite aproximado de 250 mil eventos.

Na tela **Logs para análise** existem dois exports pelo seletor de documentos do Android:

- **pacote de diagnóstico `.zip`** com `events.jsonl`, `summary.json`, `latest.json`, `manifest.json` e README do formato;
- **eventos brutos `.jsonl`** com o fluxo cronológico completo ainda retido localmente.

O `manifest.json` inclui versão do app, aparelho/Android, versão detectada do Android Auto, permissões e configurações não sensíveis. Host, usuário e senha MQTT não são incluídos no pacote.

MQTT publica em `car/vw270/<source>/<key>`, `car/vw270/event` e `car/vw270/availability`, com MQTT Discovery para valores escalares úteis ao Home Assistant.

## Build e APK

Requisitos: JDK 17, Gradle 8.13 e Android SDK 36.

```bash
gradle :app:assembleDebug
```

O APK local fica em `app/build/outputs/apk/debug/app-debug.apk`.

A esteira em `.github/workflows/android.yml` compila o APK em cada push/PR e também pode ser executada manualmente em **Actions → Build Android APK → Run workflow**.

- todo build mantém APK + SHA-256 como artifact de CI por 14 dias;
- um build iniciado por **Run workflow** cria uma **GitHub pre-release** e anexa o arquivo `.apk` diretamente, além do `.sha256`;
- portanto, para obter um APK sob demanda: execute a workflow e depois abra **Releases**.

## Instalação / primeira configuração

1. Instale o APK no telefone.
2. Abra **VW270 Telemetry**.
3. Conceda as permissões Android solicitadas, incluindo localização e permissões Car Hardware.
4. Libere a otimização de bateria e, para continuidade, permita localização em segundo plano.
5. Habilite Acesso às notificações/Acesso de uso se quiser as sondas adicionais.
6. Configure MQTT e inicie o coletor persistente.
7. Opcionalmente configure Shizuku.

## Teste decisivo: zero toque na HU

Depois da instalação/configuração inicial, deixe a HU intocada, conecte o Android Auto e procure `aa/connection_type = projection`, `aa/session_created` e `car/hardware_manager`.

| Resultado | Significado |
|---|---|
| `projection` + `session_created` aparece sozinho | o host abriu/bindou nosso Car App automaticamente; zero toque é viável com API pública |
| `projection` aparece, mas `session_created` não | telefone detecta AA, porém o host não criou a sessão |
| ao abrir VW270 Probe na HU surge `session_created` imediatamente | confirma a barreira do ciclo de vida controlado pelo host |

## Limites deliberados

Este projeto não lê CAN/ECU, não usa OBD, não requer root, não injeta código no Android Auto/Google Play Services e não envia comandos ao veículo.

## Estado

`0.1.1-poc` — probe instrumentado para S25+/Android 16 + Android Auto + VW270, com exportação de diagnóstico reproduzível.
