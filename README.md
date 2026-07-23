# DevView - Gerenciador de Telemetria e Processos Android

**DevView** é um aplicativo Android desenvolvido em Kotlin para monitorar e visualizar a atividade de processos (aplicativos) em execução no sistema, tanto em primeiro plano (foreground) quanto em segundo plano (background).

O aplicativo apresenta uma interface moderna com tema escuro (inspirada em Glassmorphism com neon cyan e purple) e inclui um Widget de Saudação para a tela inicial.

---

## 🚀 Funcionalidades

1. **Painel de Processos Escaneados:**
   - Detecção de aplicativos ativos recentemente ou em execução ativa.
   - Distinção clara entre processos de **Primeiro Plano (Foreground)** e **Segundo Plano (Background)** com chips visuais.
   - Barra de busca dinâmica em tempo real para pesquisar aplicativos pelo nome ou nome do pacote.

2. **Cards de Telemetria Expansíveis (Tempo Real):**
   - **Processador (CPU):** Uso de CPU em porcentagem com barra de progresso.
   - **Memória (RAM):** Uso de memória RAM em megabytes (MB) com barra de progresso.
   - **Internet (Rede):** Taxa de transferência de dados em tempo real (Velocidade de Upload/Download em KB/s ou MB/s).
   - **Espaço (Armazenamento):** Tamanho ocupado pelo aplicativo no disco (tamanho do APK).

3. **Widget de Saudação (`GreetingWidgetProvider`):**
   - Saudação inteligente baseada no horário do dia ("Bom dia", "Boa tarde", "Boa noite").
   - Dia da semana atual e data por extenso em português (ex: *Quinta-feira*).
   - Visualização rápida dos 3 aplicativos mais ativos recentemente no sistema.

---

## 🔒 Arquitetura e Restrições do Android (Sandboxing)

A partir do Android 7.0 (API 24) e superior, o Google implementou regras rígidas de segurança por meio do **SELinux**, bloqueando o acesso de aplicativos comuns a dados globais do diretório `/proc` (onde residem as informações de CPU/RAM de outros aplicativos).

Para contornar essa restrição técnica de forma transparente e manter a utilidade do aplicativo, o **DevView** opera com um modelo híbrido de telemetria:

* **Telemetria de Rede (Real):** Caso a permissão seja concedida, o aplicativo lê dados reais de tráfego de rede consumidos por cada UID usando a API oficial `NetworkStatsManager` do Android.
* **Tamanho de Armazenamento (Real):** O DevView calcula o tamanho real do arquivo APK em disco para todos os aplicativos instalados.
* **Telemetria do DevView (Real):** O uso de CPU e Memória RAM do próprio aplicativo DevView é calculado em tempo real usando dados de execução da JVM e do processo local (`Runtime` e `Process.getElapsedCpuTime()`).
* **Telemetria de Outros Aplicativos (Simulada):** O uso de CPU e RAM de aplicativos de terceiros é gerado por um motor de simulação dinâmica e realista. Os valores flutuam de acordo com o estado do app (apps em primeiro plano oscilam entre 5% e 35% de CPU; apps em segundo plano consomem quase zero).

---

## 🛠️ Permissões Necessárias

Para funcionar, o aplicativo requer uma permissão especial:
* **Acesso ao Uso (`PACKAGE_USAGE_STATS`):** Necessária para que o aplicativo possa ler estatísticas de atividade do sistema (`UsageStatsManager`) e o consumo de rede por aplicativo (`NetworkStatsManager`).

O aplicativo conta com uma tela integrada que orienta o usuário a conceder essa permissão diretamente nas configurações do Android caso ela não esteja ativa.

---

## 📦 Estrutura do Projeto

* `app/src/main/java/com/marcos/devview/MainActivity.kt`: Controla o ciclo de vida do painel, a busca e a requisição de permissões.
* `app/src/main/java/com/marcos/devview/telemetry/TelemetryEngine.kt`: Responsável por consultar as APIs do Android e gerenciar o motor de telemetria híbrido (real + simulado).
* `app/src/main/java/com/marcos/devview/adapter/ProcessAdapter.kt`: Controla a lista expansível da RecyclerView com animações de expansão e atualização de valores a cada 2 segundos.
* `app/src/main/java/com/marcos/devview/widget/GreetingWidgetProvider.kt`: Widget de tela inicial com dia da semana e lista rápida em português.

---

## 💻 Requisitos de Compilação

- **SDK Mínimo:** API 26 (Android 8.0)
- **SDK de Compilação:** API 34 (Android 14)
- **Linguagem:** Kotlin 1.9.22
- **Gradle:** 8.2 (configurado no wrapper)
- **Compatibilidade:** Android Studio Iguana ou superior.
