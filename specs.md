Step 1：立宪法（Constitution）
目的：先把“护栏”写死，避免 AI 自由发挥跑偏。

命令（回车）：
/prompts:speckit.constitution
内容（回车）：写项目原则，不写业务细节，重点是约束与工程规则，比如：
隐私/数据边界（本地优先、最小权限等）
安全边界（不注入不可信内容、隔离页面环境等）
性能边界（不做全量重扫、增量策略、节流等）
工程规范（TS、模块边界、lint/test）
质量门槛（必须可验收、可测试）
产物通常落在：.specify/memory/constitution.md

Step 2：写规格（Specify）
目的：只写“做什么/为什么”，把 MVP 与验收标准讲清楚，先不谈技术实现。

命令（回车）：
/prompts:speckit.specify
内容（回车）：建议结构是：
目标用户/场景（泛化描述）
MVP 能力边界（做什么）
明确不做什么（防膨胀）
关键边界情况（输入归一化/异常/权限/失败兜底）
验收标准（可以用 checklist 的方式写）
产物通常落在：specs/<feature>/spec.md

Step 2.5（可选）：澄清（Clarify）
目的：把规格里容易分歧的灰区问死，减少返工。

命令（回车）：
/prompts:speckit.clarify
回答它提出的 3–5 个问题（回车提交）。
这一步尤其适合：触发条件、数据口径、失败兜底策略、性能阈值、权限范围这类容易反复的点。

Step 3：写计划（Plan）
目的：把 spec 翻译成工程方案（模块划分、数据流、依赖、风险与降级）。

命令（回车）：
/prompts:speckit.plan
内容（回车）：一句话也够，比如：
基于 输出工程实现计划：模块边界、数据结构、构建方式、测试策略、风险与降级、里程碑。
产物通常落在：specs/<feature>/plan.md

Step 4：拆任务（Tasks）
目的：把 plan 变成可执行清单，明确依赖顺序与验收条件。

内容（回车）：建议分阶段输出：
请拆成阶段化 tasks：Setup → Foundational → Feature slices → Polish，并为每个任务写验收标准与依赖。
产物通常落在：specs/<feature>/tasks.md

给出任务的具体实现方案

Step 5：按任务实现（Implement）
目的：让 Codex 不是“随便写”，而是“按 tasks 逐条交付”。

命令（回车）：
/prompts:speckit.implement
内容（回车）：最关键的实践：一次只做一个阶段/一小段任务，避免一口气写爆。例如：
先只实现 Phase 1（Setup）相关 tasks，完成后停止，并给出 build/test 的运行方式。
为什么要分阶段？因为这能保证你每一步都能跑起来、可回退、可审查，避免“几十个文件一波流大改动”。