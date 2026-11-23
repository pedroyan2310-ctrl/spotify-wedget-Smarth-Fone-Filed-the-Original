# Compasso (Android)

Bolha flutuante que mostra, em qualquer tela do celular, a música que está
tocando — Spotify, YouTube Music, ou qualquer outro app — com capa, título,
**letra sincronizada** e efeitos visuais num cartão com visual de "vidro
líquido". Sem login, sem conta Premium: o app lê diretamente do sistema de
mídia do Android, do mesmo jeito que a tela de bloqueio e os fones Bluetooth
fazem.

> Se chamava "Agora Tocando" nas primeiras versões; o nome mudou pra
> **Compasso** (o pacote interno do app continua `com.agoratocando.floatwidget`
> — renomear isso também não muda nada pra você, só criaria risco à toa).

## Se você chegou aqui depois de um crash no celular

Se a bolha estava fechando sozinha ("Compasso apresenta falhas contínuas") ou
nem aparecia depois de instalar uma versão anterior deste app, leia a seção
**[O que mudou nesta rodada](#o-que-mudou-nesta-rodada-correção-de-crash--tema--efeitos)**
antes de instalar essa versão nova — ela explica exatamente o que foi mudado
pra tentar corrigir isso, e **com que honestidade eu consigo garantir isso
daqui**.

## Por que só Android (e não iPhone)

O iOS não tem nenhuma API pública (sem jailbreak) que deixe um app de
terceiros ler o que está tocando em outro app, no sistema inteiro. O Android
tem — é o `NotificationListenerService` + `MediaSessionManager` usados aqui.
Por isso esse projeto é um app Android nativo de verdade, não um site.

## Como funciona (arquitetura)

```
MediaNotificationListener (NotificationListenerService)
  └─ usa MediaSessionManager.getActiveSessions() pra pegar um MediaController
     de cada app tocando música no aparelho
  └─ escolhe o "melhor" (tocando > pausado > qualquer um, o mais recente)
  └─ publica título, artista, álbum, capa, posição, etc no NowPlayingBus
            │
            ▼  (StateFlow, dentro do mesmo processo)
OverlayService (LifecycleService)
  └─ desenha a bolha flutuante (WindowManager + TYPE_APPLICATION_OVERLAY)
  └─ CircularNowPlayingView: a capa em círculo + anel de progresso + partículas
     orbitando + anéis de pulso, tudo desenhado na mão (Canvas), com a cor
     extraída da capa via Palette
  └─ LyricsRepository: busca a letra (lrclib.net) quando a música muda,
     com cache em memória e 3 tentativas (exata → busca limpa → busca crua)
  └─ VisualEffects.AuroraView: fundo animado atrás do cartão, nas cores da
     capa; ShimmerHelper: brilho que varre o título periodicamente
  └─ ao tocar: expande num cartão "vidro líquido" (capa borrada via
     RenderEffect no Android 12+, aurora animada, scrim pra legibilidade) com
     título, letra sincronizada (karaoke de 3 linhas), progresso e
     play-pause/prev/next; arrastar solta a bolha na borda mais próxima com
     animação de mola (SpringAnimation); fecha sozinho depois de 15s parado
  └─ comandos (play/pause/prev/next) vão direto pro MediaController do app
     que estiver tocando — sem precisar de login em nada

MainActivity — tela única, explica e liga as duas permissões, liga/desliga a bolha
BootReceiver — religa a bolha sozinha depois que o celular reinicia (se já estava ligada)
```

## As duas permissões (e por que cada uma existe)

1. **Acesso a notificações** — é a chave que o Android exige pra qualquer
   app poder perguntar "o que está tocando?" a outros apps. O Compasso não lê
   o conteúdo das suas notificações de verdade, só usa essa permissão como
   autorização pra enxergar as sessões de mídia ativas no sistema.
2. **Exibir sobre outros apps** ("overlay") — é o que deixa a bolha flutuar
   por cima de qualquer tela.

Nenhuma das duas tem diálogo de "permitir/negar" — são permissões especiais
que só se ativam na tela de Configurações do sistema. O app abre essa tela
pra você com um toque e reconfere sozinho quando você volta.

O app também pede **Internet** (permissão "normal", concedida sozinha na
instalação, sem tela nenhuma) — é só pra buscar a letra no lrclib.net.

## Visual

Tema escuro neutro (perto do preto real, no espírito do modo escuro do
Spotify — `#0D0D0F` / `#181818` / `#282828`), mas com um degradê de
assinatura próprio, roxo `#B388FF` → ciano `#00E5C7`, em vez do verde do
Spotify — esse gradiente é o "visual único" deste projeto, igual já era
antes.

- **Bolha** (estado pequeno): capa do álbum em círculo, anel de progresso
  (`Canvas.drawArc`), brilho suave atrás na cor dominante da capa, partículas
  orbitando e anéis de pulso que nascem a cada ~1.4s — só quando a música
  está tocando (parada, a bolha fica "quieta" de propósito).
- **Cartão expandido** (estado grande): capa redonda, título com um brilho
  (shimmer) que varre periodicamente, letra sincronizada em formato karaokê
  (linha anterior / atual em destaque / próxima), barra de progresso, os três
  controles, e um fundo em camadas —
  capa borrada (API 31+) → aurora animada nas cores da capa → véu escuro
  semi-transparente (legibilidade) → conteúdo.
- A física do arraste usa `SpringAnimation` (androidx.dynamicanimation) pra
  um "solta e balança" de verdade ao encostar na borda da tela.

### Os "4 super efeitos" + Blur/Liquid Glass

| Efeito | Onde | Como funciona |
|---|---|---|
| Partículas orbitando | bolha pequena | 6 pontos girando em raios/velocidades levemente diferentes ao redor do anel |
| Anéis de pulso | bolha pequena | um anel nasce a cada ~1.4s, cresce e desaparece |
| Aurora animada | cartão expandido | dois "borrões" de luz (cores vibrante + muted da capa) derivando devagar atrás do conteúdo |
| Brilho no título | cartão expandido | uma faixa clara varre o texto do título a cada ~2.6s |
| Blur / "vidro líquido" | cartão expandido | a própria capa, borrada com `RenderEffect.createBlurEffect` (só Android 12+), como fundo do cartão |

**Importante ser honesto sobre o que "reage à música" significa aqui**: nada
disso analisa o áudio de verdade (ondas sonoras, frequência, batida). Captar
o áudio de *outro app* (Spotify, etc.) em segundo plano exigiria
`AudioPlaybackCaptureConfiguration` (Android 10+), que pede um diálogo de
consentimento de gravação de tela pro usuário e que muitos apps de streaming
bloqueiam de propósito para o próprio app deles. Em vez disso, tudo aqui reage
a **metadados e estado de reprodução**: cor da capa (Palette), se está
tocando ou pausado, e o progresso da música — o que já dá pra um visual bem
"vivo" sem pedir uma permissão pesada e sem depender de cada app permitir ou
não a captura do próprio som.

## O que mudou nesta rodada (correção de crash + tema + efeitos)

Depois do primeiro `.apk` compilado ter fechado sozinho num aparelho real
(POCO C71, "Compasso apresenta falhas contínuas", sem a notificação
persistente "Compasso ativo" aparecer — sinal de que o `OverlayService`
estava entrando em loop de crash ao iniciar), esta rodada fez três coisas:

1. **Blindagem contra o crash.** Sem um stack trace real em mãos (o Android
   só mostra "o app parou", não o motivo), a correção foi tornar o serviço
   **à prova de falhas em vez de corrigir uma linha específica**: o
   `onCreate()` inteiro do `OverlayService`, o `onListenerConnected()` e o
   `publishBest()` do `MediaNotificationListener` agora rodam dentro de
   `try/catch` amplos que gravam o erro no Logcat (tag `Compasso`) e desligam
   o serviço educadamente em vez de deixar a exceção derrubar o app inteiro
   em loop.
   **Sendo direto sobre o que isso significa**: não tenho como garantir, a
   partir daqui (sem SDK, sem emulador, sem o aparelho em mãos), que isso
   resolve a causa raiz — só garanto que, *se* o mesmo tipo de erro
   acontecer de novo, o app não vai mais travar em loop feio, e vai deixar um
   rastro no Logcat. Se crashar de novo, é isso que eu vou pedir: um
   `adb logcat -s Compasso:* AndroidRuntime:E` rodando no computador enquanto
   você abre o app, ou simplesmente abrir o Android Studio → Logcat com o
   celular conectado e copiar a mensagem vermelha que aparecer.
2. **Novo nome e novo tema.** "Agora Tocando" → **Compasso**; paleta
   repintada pra um preto/cinza neutro (em vez do roxo escuro de antes), mais
   parecido com o modo escuro real do Spotify, mantendo o degradê roxo→ciano
   como assinatura visual (ver seção *Visual* acima).
3. **O pacote de efeitos pedido**: letra sincronizada, blur/"vidro líquido" e
   os 4 super efeitos, descritos em detalhe acima.

### O que eu consegui verificar e o que eu não consigo garantir

Sem Android SDK/emulador neste ambiente (o mesmo motivo de sempre: o sandbox
onde este projeto é escrito não tem como baixar nem rodar as ferramentas do
Android), o que foi verificado desta vez, de novo por script, em cima de
**todos** os arquivos do projeto (os de antes + os novos/alterados agora):

- Todo XML (25 arquivos) é bem formado.
- Toda chave/parêntese/colchete de todo arquivo Kotlin (10 arquivos) fecha
  corretamente.
- Todo recurso citado no código (`R.string`, `R.color`, `R.drawable`,
  `R.style`, `R.layout`) existe de fato nos arquivos de recurso.
- Todo `binding.algumId` usado no `OverlayService.kt`/`MainActivity.kt` tem
  um `android:id` correspondente no layout que aquele binding infla — ou
  seja, a reestruturação do `overlay_widget.xml` (pra empilhar
  capa-borrada/aurora/véu/conteúdo) não deixou nenhuma referência órfã.

O que isso **não** cobre, porque só um aparelho real (ou um emulador) mostra:
se o blur realmente aparece suave num POCO C71 real, se a letra chega a
tempo sem travar a animação, se o layout fica bonito no tamanho de tela
específico do seu aparelho, e se a blindagem contra crash realmente resolveu
o problema que você teve. **Esta versão está pronta pra você testar de novo —
não estou dizendo que ela "com certeza funciona", porque essa certeza só
existe depois de rodar num celular de verdade, e eu não tenho um aqui.** Se
algo não aparecer do jeito esperado, me manda print (ou o Logcat, se crashar
de novo) que eu continuo ajustando a partir disso.

## Estrutura do projeto

```
agora-tocando-android/
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts              (dependências, versões)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/agoratocando/floatwidget/
        │   ├── NowPlayingBus.kt               (estado + canal entre os dois serviços)
        │   ├── MediaNotificationListener.kt   (descobre o que está tocando)
        │   ├── ProgressMath.kt                (interpolação local de progresso/posição)
        │   ├── LyricsRepository.kt            (busca + cache de letra, lrclib.net)
        │   ├── CircularNowPlayingView.kt       (a bolha: capa, anel, partículas, pulsos)
        │   ├── VisualEffects.kt               (AuroraView + ShimmerHelper)
        │   ├── OverlayService.kt              (janela flutuante, arraste, controles, liga tudo)
        │   ├── MainActivity.kt                (permissões + liga/desliga)
        │   ├── BootReceiver.kt                (religa após reiniciar)
        │   └── Prefs.kt                       (se a bolha deve estar ligada)
        └── res/
            ├── layout/   (overlay_widget.xml, activity_main.xml)
            ├── drawable/ (ícones vetoriais, fundos, contorno do cartão)
            ├── mipmap-anydpi-v26/ (ícone adaptativo do app)
            └── values/   (strings, cores, tema)
```

## Como conseguir o .apk

Este projeto é código-fonte, não um app pronto — ninguém consegue compilar um
.apk de verdade sem passar pelas ferramentas do Android (SDK, Gradle,
compilador), que não existem no ambiente onde ele foi escrito. Duas formas de
chegar num .apk instalável a partir daqui:

**Opção A — Android Studio (mais comum, deixa você mexer no projeto depois)**

1. Instale o **Android Studio** (gratuito, versão atual).
2. `Open` nesta pasta (`agora-tocando-android`) — ele baixa sozinho o Gradle
   8.9, o AGP 8.5.2 e todas as dependências na primeira sincronização
   (precisa de internet nessa hora).
3. Rode no celular (USB, com "Depuração USB" ativada) ou num emulador com
   Google Play. Isso já compila e instala o app — não precisa "exportar"
   nada pra só usar.
4. Se quiser o arquivo `.apk` em si (pra mandar pra outra pessoa, por
   exemplo): menu `Build` → `Build App Bundle(s) / APK(s)` → `Build APK(s)`.

**Opção B — build automático na nuvem (sem instalar nada no computador,
já testado e confirmado funcionando por você mesmo numa rodada anterior)**

O projeto já vem com `.github/workflows/build-apk.yml`. Se você criar um
repositório gratuito no GitHub e subir esse código pra lá (pelo **GitHub
Desktop**, não pelo upload do site — o upload do site já demonstrou que
embaralha pastas profundas como a deste projeto), o próprio GitHub compila o
app sozinho e deixa o `.apk` pronto pra baixar em alguns minutos, na aba
**Actions** → o build mais recente → **Summary** → **Artifacts** →
`agora-tocando-debug-apk`.

Na primeira abertura do app (depois de instalado, por qualquer um dos dois
caminhos), toque em "Ativar" nas duas permissões (abre a tela de
Configurações do sistema, volta sozinho pro app) e depois em "Ativar a bolha
flutuante".

## Limitações conhecidas / o que ficou de fora de propósito

- **Nunca foi compilado nem rodado por mim.** Eu (a IA que escreveu isto) não
  tenho Android SDK nem emulador neste ambiente — só validação estática
  (descrita acima). A única vez que este projeto rodou de verdade foi no seu
  aparelho, e foi aí que apareceu o crash que esta rodada tentou blindar. Eu
  encaro esse ciclo (você testa num aparelho real → me conta o que
  aconteceu → eu ajusto) como o jeito certo de chegar num resultado
  confiável, em vez de prometer uma certeza que eu não tenho como checar
  sozinho.
- **Cobertura de letra depende do lrclib.net.** É um banco de dados livre e
  colaborativo — música muito nova, muito nichada, ou com título/artista
  grafado diferente do original pode não ser encontrada. Quando isso
  acontece, o cartão mostra "Letra não encontrada" (não é um erro, é
  realmente a letra não estando lá).
- **Blur/"vidro líquido" só em Android 12 (API 31) ou mais novo** — é uma
  limitação do próprio Android (`RenderEffect` não existe em versões
  anteriores). Em aparelhos mais antigos o cartão expandido mostra a aurora
  animada + o fundo escuro normal, sem a capa borrada atrás.
- **Efeitos "reagem à música" via metadados/estado, não via áudio real** —
  ver a explicação detalhada na seção *Visual* acima.
- **Sem troca manual de fonte** quando mais de um app toca música ao mesmo
  tempo — o app escolhe sozinho (prioriza quem está tocando de verdade).
- Ícone do app e todos os ícones de controle são vetores (XML) desenhados à
  mão, sem nenhuma imagem externa.

## Próximos passos possíveis (se quiser)

- Capturar um Logcat real do crash original (se ele voltar a acontecer) pra
  corrigir a causa exata, em vez de só a blindagem genérica desta rodada.
- Opção de trocar manualmente qual app controlar, pra quem usa dois tocando
  junto.
- Tema claro opcional (hoje é sempre escuro, de propósito).
