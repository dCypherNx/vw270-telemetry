# VW270 Telemetry

PoC Android para extrair o **máximo de telemetria observável sem root e sem OBD** de um VW Polo/VW270 durante uma sessão Android Auto.

O projeto é deliberadamente **read-only**: não envia comandos ao veículo, não abre CAN/OBD, não altera Android Auto/Google Play Services e não disputa a conexão USB com a head unit.

## Estado atual: 0.1.3-poc

O teste real da `0.1.2-poc` no S25+/Android 16 confirmou:

- `CarConnection` detecta `projection` de forma confiável;
- a HU cria um Android Open Accessory identificado como **Android Auto**;
- o Android Auto cria seu display virtual e rotas internas de áudio;
- não surge uma interface de rede específica da HU;
- não surge um `InputDevice` da HU;
- o nosso `CarAppService` instalado por sideload não é apresentado pela HU, portanto `CarHardwareManager` não é um caminho operacional neste cenário sem distribuição confiável pelo Google Play.

Com isso, a `0.1.3` removeu do caminho investigativo o Car App projetado, Car Hardware, Usage Stats, Notification Listener e os snapshots genéricos de rede/input/áudio/media-route. O foco agora é a **superfície de providers exportados pelo próprio Android Auto no telefone**.

## Sonda Android Auto 0.1.3

Quando `aa/connection_type` muda para `projection`, o app:

1. inventaria os `ContentProvider` exportados pelo pacote `com.google.android.projection.gearhead`, incluindo permissões, `pathPermissions`, `uriPermissionPatterns`, processo e chaves de metadata;
2. usa somente operações read-only (`getType()` e `query()`);
3. consulta de forma limitada os providers-alvo:
   - `androidx.car.app.connection` — estado oficial da projeção;
   - `com.google.android.gearhead.shared_preferences_provider` — configuração/estado compartilhado exposto pelo Android Auto;
   - `com.google.android.projection.gearhead.troubleshooter_provider` — superfície de diagnóstico/troubleshooting;
   - `com.google.android.projection.gearhead.color_provider` — controle simples para validar acesso a providers do processo de projeção;
4. repete a leitura no início, em +5 s, +15 s e depois a cada 30 s;
5. registra também um snapshot imediatamente antes da desconexão e outro após a desmontagem da sessão.

Providers relacionados a microfone, arquivos de bugreport, ícones e developer-settings são explicitamente ignorados porque não são relevantes para telemetria estruturada ou podem expor mídia/arquivos.

As consultas são limitadas a 20 linhas e 32 colunas por provider. BLOBs nunca são persistidos; campos com aparência de senha/token/credencial são redigidos antes de entrar no SQLite ou no ZIP de diagnóstico.

## Transporte ainda observado

USB e Bluetooth permanecem como contexto de sessão, não como fontes de telemetria do veículo:

- USB registra devices/accessories e apenas `hasPermission`; o app **não abre** o accessory Android Auto;
- Bluetooth registra dispositivos pareados/conectados e UUIDs SDP já disponíveis no sistema, sem endereço MAC no log novo.

Rede, Wi-Fi, áudio e input genéricos foram removidos da sonda porque o primeiro teste real não mostrou um canal útil de telemetria do VW270 nessas superfícies.

## Telemetria do telefone

Durante a projeção permanecem ativos:

- Fused Location/GNSS;
- satélites GNSS;
- sensores do telefone;
- velocidade estimada por GNSS;
- bateria/estado térmico;
- Bluetooth e USB como contexto de transporte.

Essas fontes são úteis operacionalmente mesmo quando nenhum valor real do veículo é encontrado.

## Shizuku

Shizuku continua no projeto como **fallback opcional**, mas não é necessário para o teste `0.1.3`. A estratégia é esgotar primeiro os providers públicos e, somente se necessário, avançar para sondas Binder estreitas e read-only.

Não existe executor shell genérico no app.

## Logs para análise

Todos os eventos são persistidos em `telemetry.db`, com retenção aproximada de 7 dias / 250 mil eventos.

Na tela **Logs para análise**:

- **Exportar pacote de diagnóstico (.zip)** gera `manifest.json`, `summary.json`, `latest.json`, `events.jsonl` e README;
- **Exportar eventos brutos (.jsonl)** gera apenas a sequência cronológica.

O formato atual do pacote é `vw270-telemetry-diagnostics-v2`. Credenciais MQTT não são exportadas e valores de provider potencialmente sensíveis são redigidos antes do armazenamento.

## MQTT / Home Assistant

MQTT publica em:

- `car/vw270/<source>/<key>`;
- `car/vw270/event`;
- `car/vw270/availability`.

Diagnósticos brutos como `aa_provider/*`, `probe/*` e `usb/*` permanecem somente no SQLite por padrão.

## Build e APK

Requisitos: JDK 17, Gradle 8.13 e Android SDK 36.

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
```

A GitHub Action **Build Android APK** roda em push/PR e manualmente. Um `main` válido atualiza a pre-release fixa:

**Releases → VW270 Telemetry · Latest Debug**

Ela contém o APK direto e o respectivo SHA-256; o artifact de CI também permanece disponível temporariamente.

## Instalação / primeiro teste da 0.1.3

1. Instale a `0.1.3-poc`.
2. Conceda localização, atividade física, Bluetooth e notificações quando solicitadas.
3. Libere otimização de bateria e, para teste em segundo plano, localização sempre permitida.
4. Inicie o coletor persistente **antes de conectar ao carro**.
5. Espere alguns segundos desconectado para registrar o baseline.
6. Conecte o Android Auto normalmente, sem procurar o VW270 Telemetry na HU.
7. Deixe a sessão ativa por pelo menos 60–90 s.
8. Desconecte e espere pelo menos 5 s.
9. Exporte o ZIP de diagnóstico e analise `aa_provider/*`, `probe/provider_inventory`, `usb/transport` e `bluetooth/*`.

O resultado mais importante desta rodada é descobrir se `shared_preferences` ou `troubleshooter` revelam estado/capabilities da sessão que mudam de forma correlacionada com a HU. Se esses providers forem bloqueados ou exigirem paths específicos, o inventário de `pathPermissions`/`uriPermissionPatterns` orientará a próxima sonda sem voltar a uma varredura ampla.
