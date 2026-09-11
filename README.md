# VW270 Telemetry

PoC Android para obter telemetria de um VW Polo/VW270 **sem root e sem OBD**, usando a sessão Android Auto.

O projeto é deliberadamente **read-only**: não implementa comandos de atuação/configuração do veículo e não disputa a conexão USB com a head unit.

## Estado atual: 0.1.4-poc · VAG MIB2 ExLAP

Os testes 0.1.2/0.1.3 no S25+/Android 16 provaram que o telefone enxerga a projeção Android Auto, USB accessory e contexto de sessão, mas não recebe telemetria real pelas APIs públicas que sondamos. O `CarAppService` sideloadado também não é apresentado pela HU sem distribuição confiável pelo Google Play.

A identificação da central `5G0 035 280 C` mudou o alvo: ela pertence à família Volkswagen MIB2, para a qual existe o canal de vendor extension do Android Auto chamado:

`com.vwag.infotainment.gal.exlap`

ExLAP é usado por implementações VAG MIB2 para disponibilizar dados reais do veículo à camada Android Auto. A `0.1.4` abandona a investigação genérica de providers/Shizuku e testa diretamente esse caminho.

## Como a 0.1.4 funciona

O app usa a API legada de Android Auto Vendor Extension apenas para abrir o canal ExLAP. O AAR antigo do SDK contém recursos incompatíveis com o AAPT2 atual, então o build extrai **somente `classes.jar`** do SDK legado; nenhum recurso/UI antigo é incorporado.

Ao conseguir acesso ao canal, a sonda executa somente o protocolo necessário para receber dados:

1. abre a sessão ExLAP;
2. negocia protocolo/capabilities;
3. autentica usando SHA-256 (`useHash="sha256"`);
4. solicita o diretório de URLs;
5. lê interfaces/schema;
6. assina os objetos anunciados pela própria HU;
7. persiste e publica os `Dat` recebidos como `exlap/*`.

Não existem operações de escrita em parâmetros do veículo. Os únicos frames enviados são handshake, autenticação, descoberta e `Subscribe`.

## Resultado decisivo

Na tela **Estado ao vivo**, a sequência esperada é aproximadamente:

- `exlap/permission = true`;
- `exlap/channel = true`;
- `exlap/authentication = true`;
- `exlap/directory = <quantidade de URLs>`;
- `exlap/schema = <quantidade de campos>`;
- depois, valores `exlap/*` variando com o carro.

Se `exlap/channel` ficar `unavailable`, a HU/Android Auto atual não está anunciando o vendor channel para o nosso processo. Se o canal abrir, mas a autenticação falhar, o problema está no protocolo/credenciais/compatibilidade de firmware. Se surgirem valores `exlap/*`, teremos prova de telemetria real originada do veículo.

## Fallback do telefone

Durante projeção permanecem disponíveis, separadamente:

- Fused Location/GNSS;
- satélites GNSS;
- sensores do telefone;
- velocidade estimada por GNSS;
- bateria/estado térmico;
- USB/Bluetooth como contexto.

Esses dados nunca devem ser confundidos com `exlap/*`, que é o namespace reservado para a telemetria recebida da HU.

## Logs para análise

Todos os eventos são persistidos em `telemetry.db`, com retenção aproximada de 7 dias / 250 mil eventos.

O pacote ZIP (`vw270-telemetry-diagnostics-v3`) contém `manifest.json`, `summary.json`, `latest.json`, `events.jsonl` e README. Credenciais MQTT e credenciais de protocolo ExLAP não são exportadas; nonce/cnonce/digest são redigidos nos registros de handshake.

## MQTT / Home Assistant

MQTT publica em:

- `car/vw270/<source>/<key>`;
- `car/vw270/event`;
- `car/vw270/availability`.

Valores `exlap/*` são publicáveis normalmente; eventos internos de handshake ficam somente no diagnóstico local.

## Build

Requisitos: JDK 17, Gradle 8.13 e Android SDK 36.

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
```

A GitHub Action **Build Android APK** roda em push/PR e manualmente. Builds válidos em `main` atualizam a pre-release fixa **VW270 Telemetry · Latest Debug** com APK e SHA-256.

## Primeiro teste da 0.1.4

1. Instale a `0.1.4-poc`.
2. Abra o app e toque **Conceder permissões do Android / ExLAP**.
3. Inicie o coletor persistente ainda desconectado do carro.
4. Conecte o Android Auto normalmente; não procure nem abra o VW270 Telemetry na HU.
5. Observe `exlap/*` no estado ao vivo e mantenha a sessão ativa por 1–2 minutos.
6. Faça um pequeno deslocamento se `exlap/channel` e `authentication` estiverem com sucesso.
7. Desconecte, espere alguns segundos e exporte o ZIP de diagnóstico.

Esta rodada tem um objetivo único: determinar se a combinação Android Auto atual + MIB2 `5G0035280C` ainda permite que um APK sideloadado receba o canal ExLAP.
