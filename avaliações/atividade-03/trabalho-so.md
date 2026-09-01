# Especificação do Gerenciador de Processo de um Simulador de Sistema Operacional

## 1. Visão Geral e Arquitetura do Simulador

### 1.1 Objetivo
O sistema deve simular um gerenciador de processos de um sistema operacional em **modo usuário**.

### 1.2 CPU Virtual
Executa apenas um processo por vez, alternando o controle através de um algoritmo de escalonamento.

| Componente | Descrição |
| ---------- | ---------- |
| **Registradores Gerais** | Vetor simplificado de valores a serem salvos/restaurados na troca de contexto de um processo. |
| **Contador de Programa (PC)** | Inteiro representando a posição lógica de execução dentro do programa do processo (ex: índice na sequência de operações do arquivo de tarefas). |
| **Relógio Lógico (*Clock*)** | Variável Inteira global incrementada a cada interação do laço principal do simulador. |
| **Interrupção de relógio** | Evento disparado quando o relógio lógico atinge o limite do *quantum* do processo atual, forçando o escalonador a realizar a troca de contexto (preempção). |
| **Tempo de Troca de Contexto** | Constante `TEMPO_TROCA_CONTEXTO` (em ticks) que define o custo de CPU para salvar e restaurar o contexto (*dispatcher*). |

### 1.3 Fluxo Geral de Execução

**Laço Principal do Simulador**
1. Ler os eventos do arquivo de tarefas correspondentes ao relógio lógico atual (ex: chegada de novos processos).
2. Avançar o relógio lógico em 1 tick.
3. Atualizar estado do Dispositivo de E/S:
    - Se a fila de BLOQUEADO não estiver vazia, decrementar o `surto_es_restante` apenas do processo na **cabeça** da fila (detentor do dispositivo de E/S de acesso exclusivo).
    - Se o `surto_es_restante` deste processo chegar a 0 -> move o processo para PRONTO (desbloqueio). O próximo processo da fila de BLOQUEADO assume o dispositivo.
4. Notificar escalonador e avaliar preempção:
    - Invocar `notificar_tick()`. Se o retorno for `true` (expiração de quantum ou nova prioridade), forçar a preempção do processo EM_EXECUCAO para PRONTO.
5. Tratamento de Troca de Contexto (*Dispatcher*):
    - Se uma troca de contexto estiver em andamento, decrementar o contador de troca. O laço encerra o tick atual aqui (CPU em *overhead*).
6. Se a CPU estiver ociosa e houver processos em PRONTO:
    - Invocar `selecionar_proximo()`.
    - Iniciar o contador de `TEMPO_TROCA_CONTEXTO`.
7. Executar processo corrente:
    - Decrementar `surto_cpu_restante`.
    - Se `surto_cpu_restante` chegar a 0 -> o processo solicita E/S (move para BLOQUEADO) ou termina (chamada `exit`).
8. Registrar no log quaisquer transições de estado ocorridas neste tick.
9. Repetir o laço até que a tabela de processos ativos (não terminados) e o arquivo de tarefas estejam vazios.

## 2. Especificação do Bloco de Controle de Processo (PCB) e Tabela de Processos

### 2.1 Estrutura do PCB

Cada processo deve ser representado por um registro (struct) como o modelo abaixo:

```c
typedef enum {
    PRONTO,
    EM_EXECUCAO,
    BLOQUEADO,
    TERMINADO   // estado terminal, fora do ciclo ativo
} EstadoProcesso;

typedef struct {
    int pid;                    // identificador único do processo
    EstadoProcesso estado;      // estado atual

    int registradores[4];       // vetor de registradores gerais
    int pc;                     // contador de programa lógico

    // para escalonamento
    int prioridade;             // nível de prioridade
    int quantum_restante;       // tempo ainda disponível em quantum (fatias de tempo)

    // contabilização para saída
    int tempo_cpu_total;        // ticks efetivamente executados na CPU
    int tempo_espera_total;     // ticks acumulados no estado PRONTO
    int tempo_bloqueado_total;  // ticks acumulados no estado BLOQUEADO
    int tempo_chegada;          // tick em que processo foi criado
    int tempo_termino;          // tick em que processo terminou

    // execução corrente
    int surto_cpu_restante;     // duração do surto de CPU em andamento
    int surto_es_restante;      // duração da E/S em andamento (quando BLOQUEADO)
} PCB;
```

Campos obrigatórios mínimos: `pid`, `estado`, `registradores`, `pc`, `prioridade`, `tempo_cpu_total`, `tempo_espera_total`.

### 2.2 Tabela de Processos
* Estrutura de dados global que mantém **todos** os processos, independentemente do estado.
* Deve permitir busca por pid.
* As filas de escalonamento **não duplicam** os PCBs; contêm apenas ponteiros para entradas da tabela de processos, evitando inconsistências de estado.

## 3. Ciclo de Vida e Grafo de Transição de Estados

### 3.1 Estados
- **PRONTO:** O processo aguarda na fila até que a CPU seja liberada para ele.
- **EM_EXECUCAO:** O processo está ativamente utilizando a CPU.
- **BLOQUEADO:** O processo não pode executar no momento pois aguarda evento externo (Entrada/Saída no dispositivo exclusivo).
- **TERMINADO:** Estado final utilizado para processos que já encerraram.

### 3.2 Especificação das Transições

| Transição | Evento disparador | Ação |
| :--- | :--- | :--- |
| **0** (criação -> PRONTO) | `fork` simulado via leitura do arquivo de tarefas. | Aloca PCB, atribui PID, define `pc = 0` e `estado = PRONTO`, insere na estrutura do escalonador, registra `tempo_chegada`. |
| **1** (EM_EXECUCAO -> BLOQUEADO) | Processo esgota surto de CPU e solicita E/S. | Salva contexto no PCB; insere no **final** da fila do dispositivo de E/S configurando o `surto_es_restante`; invoca escalonador para liberar a CPU. |
| **2** (EM_EXECUCAO -> PRONTO) | Preempção (sinalizada internamente por `notificar_tick()`). | Salva contexto; insere processo na estrutura de prontos conforme a política do escalonador ativo; invoca escalonador. |
| **3** (PRONTO -> EM_EXECUCAO) | Escalonador seleciona processo. | **CPU consome `TEMPO_TROCA_CONTEXTO` ticks para o despacho.** Restaura registradores/PC; `quantum_restante` (se aplicável) é recarregado. |
| **4** (BLOQUEADO -> PRONTO) | E/S concluída: `surto_es_restante` do processo na **cabeça** da fila de E/S chega a 0. | Processo é movido para PRONTO. O escalonador é notificado. O próximo processo bloqueado inicia o uso do dispositivo. |
| **5** (EM_EXECUCAO -> TERMINADO) | Chamada `exit`: surto final da sequência do processo é concluído. | Registra `tempo_termino`; libera CPU; invoca escalonador. O PCB permanece na tabela apenas para estatísticas. |

### 3.3 Regras de Consistência
* Toda transição deve ser registrada em log no formato: `tick`, `pid`, `estado_anterior`, `estado_novo`, `evento_causador`.
* Um processo **NUNCA** pode sair diretamente de BLOQUEADO para EM_EXECUCAO - deve obrigatoriamente passar por PRONTO, refletindo o fato de que o desbloqueio apenas o torna elegível, e a escolha de quem executa é sempre prerrogativa do escalonador.
* O tempo em cada estado deve ser contabilizado a cada tick (incrementar `tempo_espera_total` enquanto PRONTO, `tempo_bloqueado_total` enquanto BLOQUEADO).

## 4. Especificação do Escalonador de CPU
Escalonador projetado como **componente intercambiável**, permitindo alternar entre algoritmos sem alterar o restante do simulador.

### 4.1 Escalonamento Circular (Round Robin)
* Fila **circular** (FIFO) de processos no estado PRONTO.
* Parâmetro configurável: **quantum Q** (em ticks).
* Regras:
    1. O processo na cabeça da fila é despachado para EM_EXECUCAO (transição 3) e recebe `quantum_restante = Q`.
    2. A cada tick executado, `quantum_restante` é decrementado.
    3. Se o processo bloqueia (pede E/S) antes de `quantum_restante` chegar a 0 -> transição 1; ao ser desbloqueado, volta ao **final** da fila.
    4. Se `quantum_restante` chegar a 0 antes do processo terminar ou bloquear -> transição 2 (interrupção de relógio); processo retorna ao final da fila.
    5. Se o processo termina antes de esgotar o quantum -> transição 5; próximo da fila é despachado.
* Deve ser possível variar o quantum entre execuções de teste para observar seu impacto no tempo de resposta.

### 4.2 Escalonamento por Prioridade (estática ou dinâmica)
* Cada processo possui um campo `prioridade` (quanto **maior** o valor, maior a prioridade).
* **Prioridade estática**: atribuídas na criação do processo e nunca alteradas.
* **Prioridade dinâmica**: a prioridade é recalculada periodicamente em função do tempo de espera acumulado (`tempo_espera_total`) e do histórico de CPU, de modo a favorecer processos que ficaram muito tempo sem executar.
* **Prevenção de inanição (*starvation*)**: obrigatória. A especificação deve descrever o mecanismo escolhido:
    * **Aging**: a cada 5 ticks em que um processo permanece PRONTO sem ser escolhido, sua prioridade efetiva é incrementada (aproximada da mais alta), até ser despachado.
    * **Escalonamento por múltiplas filas com quantum crescente por fila** (semelhante ao esquema de classes de prioridade com Round Robin dentro de cada classe).
* Dentro de uma mesma classe/nível de prioridade, os processos empatados devem ser desempatados por ordem de chegada (FIFO).

### 4.3 Interface Comum do Escalonador
Independente do algoritmo, o módulo de escalonamento deve expor as operações:

| Operação | Responsabilidade |
| :--- | :--- |
| `inserir_pronto(pcb)` | Insere um processo na estrutura de prontos do algoritmo ativo. |
| `selecionar_proximo()` | Retorna o próximo PCB a ser despachado, ou `nulo` se não há processos prontos. |
| `notificar_tick()` | Atualiza contadores internos (quantum, aging) e **retorna um booleano** indicando se a preempção deve ocorrer. |
| `remover(pid)` | Remove um processo da estrutura (uso em `exit`). |

## 5. Entradas, Casos de Teste e Diretrizes de Entrega

### 5.1 Arquivo de Tarefas (entrada)
Formato padronizado estrito, garantindo leitura linear do parser. Formato: `[COMANDO] [PID] [PARAM1] [PARAM2]`

```text
CRIA 1 CHEGADA=0 PRIORIDADE=2
CPU 1 DURACAO=4
ES 1 DURACAO=3
CPU 1 DURACAO=2
EXIT 1

CRIA 2 CHEGADA=1 PRIORIDADE=1
CPU 2 DURACAO=6
EXIT 2
```
Cada processo é descrito por uma sequência alternada de surtos de CPU e de E/S, terminando sempre com `EXIT`.

### 5.2 Saída do Simulador
O simulador deve produzir, ao final da execução:

1. **Gráfico de Gantt textual**, mostrando tick a tick (ou por intervalo) qual processo ocupou a CPU. Estados de *overhead* devem ser indicados (ex: 'T'):
   ```text
   Tick:   0  1  2  3  4  5  6  7  8  9 10
   CPU:    T  1  1  1  T  2  2  2  2  2  1
   ```
2. **Log de transições de estado**, no formato definido na Seção 3.3:
   ```text
   [tick=4] PID=1: EM_EXECUCAO -> BLOQUEADO (evento=SOLICITACAO_ES)
   [tick=5] PID=2: PRONTO -> EM_EXECUCAO (evento=ESCALONADOR)
   ```
3. **Estatísticas agregadas por processo e globais**, incluindo, no mínimo:
   * Tempo de retorno (*turnaround time*) = `tempo_termino - tempo_chegada`
   * Tempo de espera total (`tempo_espera_total`)
   * Tempo de resposta (tick da primeira vez em EM_EXECUCAO menos `tempo_chegada`)
   * Utilização da CPU (percentual de ticks em que a CPU executou código útil de processo)
   * *Throughput* (processos concluídos por unidade de tempo)

### 5.3 Casos de Teste Obrigatórios

| Caso | Objetivo |
| :--- | :--- |
| **CT1 — Fluxo básico** | Um único processo, sem E/S, apenas para validar criação → execução → término. |
| **CT2 — Alternância CPU/E/S** | Dois ou mais processos com surtos de E/S, validando contenção no hardware e as transições 1 e 4. |
| **CT3 — Expiração de quantum (Round Robin)** | Processos com surto de CPU maior que o quantum, validando a transição 2 e o retorno ao final da fila circular. |
| **CT4 — Prioridades e starvation** | Processo de baixa prioridade em fila junto de processos de alta prioridade que chegam continuamente; validar que o mecanismo de prevenção de inanição funciona. |
| **CT5 — Comparação de algoritmos** | Mesma carga executada sob Round Robin e Prioridades, comparando estatísticas e o impacto do tempo de troca de contexto. |
