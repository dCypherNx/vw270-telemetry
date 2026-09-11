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

Cada `CarValue` grava:

- valor;
- `SUCCESS`, `UNIMPLEMENTED`, `UNAVAILABLE` ou `UNKNOWN`;
- timestamp original;
- `carZones`;
- timestamp de recepção no telefone.

Isto é importante: ausência de dado deixa de parecer uma falha genérica do aplicativo e passa a ser mensurável.

### 2. Telefone

Durante uma projeção Android Auto, o serviço habilita automaticamente:

- **todos** os sensores retornados por `SensorManager.TYPE_ALL`, registrados com `SENSOR_DELAY_FASTEST`; movimento/rotação são persistidos até ~10 Hz por sensor e os demais até ~1 Hz;
- inventário completo dos sensores Samsung/Android e respectivos metadados;
- Fused Location em alta precisão;
- velocidade/bearing/altitude/precisões do GNSS;
- distância integrada de sessão;
- quantidade de satélites, usados no fix e constelações;
- bateria/corrente/tensão/temperatura do telefone;
- estado térmico e economia de energia;
- rede ativa;
- adaptador Bluetooth, dispositivos pareados e dispositivos conectados nos perfis A2DP/HEADSET;
- dispositivos de áudio de entrada/saída, inventário USB e estado Wi-Fi, úteis para identificar o transporte/endpoint da projeção;
- versão instalada do Android Auto.

Fora do carro os coletores de alta frequência são desligados para evitar consumo inútil. O serviço leve continua observando a conexão Android Auto.

### 3. Estado do Android Auto

Sem depender do `CarAppService`:

- `CarConnection` informa `not_connected`, `native` ou `projection`;
- `UsageStatsManager` observa atividade do pacote `com.google.android.projection.gearhead`;
- `NotificationListenerService` registra apenas notificações publicadas pelo pacote do Android Auto.

O listener de notificações **não** coleta notificações de outros aplicativos.

### 4. Shizuku opcional, sem root

Se o usuário instalar/iniciar Shizuku por Wireless Debugging e conceder permissão, a PoC executa apenas sondas de leitura:

- serviços do Android Auto;
- processos relacionados a Android Auto/projection/Car App;
- dump filtrado do pacote `com.google.android.projection.gearhead`;
- estado Bluetooth filtrado;
- estado de localização/GNSS filtrado;
- trecho de `logcat` filtrado para Android Auto, projection e Car Hardware.

A saída bruta fica no SQLite local e **não vai ao MQTT por padrão**. Sobre MQTT é publicado apenas um resumo com exit code, número de linhas e SHA-256.

Shizuku não é necessário para a telemetria normal. É uma ferramenta para investigar a barreira de criação automática da sessão sem root.

## Persistência e MQTT

Todos os eventos são gravados em `telemetry.db` antes de serem descartados da memória. Política atual da PoC:

- retenção: 7 dias;
- limite aproximado: 250 mil eventos;
- exportação manual de até 50 mil eventos para JSON Lines (`.jsonl`).

MQTT publica:

```text
car/vw270/<source>/<key>
car/vw270/event
car/vw270/availability
```

O primeiro é retido; `event` é o stream não retido. Eventos rápidos ficam integrais no SQLite, mas cada chave MQTT é limitada a 5 Hz.

Para valores escalares de `car`, `fused`, `aa`, `system` e `gnss`, o APK publica MQTT Discovery automaticamente em:

```text
homeassistant/sensor/vw270_<source>_<key>/config
```

## Build

Requisitos:

- JDK 17;
- Gradle 8.13;
- Android SDK 36;
- Android Studio recente também pode abrir o projeto diretamente.

```bash
gradle :app:assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A CI em `.github/workflows/android.yml` compila e executa lint em cada push/PR e publica o APK de debug como artifact.

## Instalação / primeira configuração

A primeira preparação é toda feita no telefone, exceto a habilitação do app no Android Auto caso o host exija sideload/developer mode.

1. Instale o APK no telefone.
2. Abra **VW270 Telemetry** no telefone.
3. Toque em **Conceder permissões do Android**. O onboarding inclui localização, `CAR_SPEED`, `CAR_MILEAGE`, `CAR_FUEL`, Bluetooth e notificações quando aplicável.
4. Libere a otimização de bateria.
5. Habilite **Acesso às notificações** e **Acesso de uso** se quiser as sondas adicionais.
6. Configure MQTT e salve.
7. Inicie o coletor persistente.
8. Opcional: configure Shizuku e conceda a permissão ao app.

Para sideload no Android Auto, normalmente é necessário habilitar o modo desenvolvedor do Android Auto e **Fontes desconhecidas**. Em alguns hosts/versões, o Google pode não listar apps sideloaded; nesses casos uma faixa Internal/Closed Testing da Play pode ser necessária mesmo com o APK correto.

## Teste decisivo: zero toque na HU

Depois da instalação/configuração inicial:

1. deixe a HU intocada;
2. conecte o Android Auto;
3. espere a telemetria registrar `aa/connection_type = projection`;
4. procure `aa/session_created` e `car/hardware_manager`.

Interpretação:

| Resultado | Significado |
|---|---|
| `projection` + `session_created` aparece sozinho | o host abriu/bindou nosso Car App automaticamente; zero toque é viável com API pública |
| `projection` aparece, mas `session_created` não | telefone detecta AA, porém o host não criou a sessão; Car Hardware ainda está bloqueado pelo ciclo de vida da HU |
| ao abrir VW270 Probe na HU surge `session_created` imediatamente | confirma exatamente a barreira do host, sem confundir com permissões ou falha de MQTT |

Antes de tocar na HU, execute/exporte um snapshot Shizuku. Depois abra o app uma única vez e exporte outro. O diff dessas duas capturas é o melhor caminho para descobrir se existe um gatilho legítimo e reproduzível no telefone.

## Limites deliberados

Este projeto **não**:

- lê CAN ou ECU;
- usa OBD;
- requer root;
- injeta código no Android Auto/Google Play Services;
- modifica pacotes do sistema;
- tenta contornar SELinux/permissões do host;
- envia comandos ao veículo;
- coleta notificações de aplicativos que não sejam Android Auto.

Sem OBD/CAN ou uma API OEM, não há como prometer RPM, marcha, temperatura do motor, ângulo de direção, velocidades individuais das rodas, status de portas etc. Se algum desses dados aparecer em logs/APIs adicionais do host, a PoC os revelará; eles não são inventados como capacidade garantida.

## Referências

- Android for Cars — Car Hardware APIs: https://developer.android.com/training/cars/apps/library/car-hardware-api
- Android for Cars — Request permissions: https://developer.android.com/training/cars/apps/library/request-permissions
- `CarConnection`: https://developer.android.com/reference/androidx/car/app/connection/CarConnection
- Shizuku API: https://github.com/RikkaApps/Shizuku-API

## Estado

`0.1.0-poc` — primeiro probe instrumentado para S25+/Android 16 + Android Auto + VW270.
