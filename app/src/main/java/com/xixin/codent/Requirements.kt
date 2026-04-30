package com.xixin.codent

class Requirements {
}


/*
好的，这里给你一版精简、工程化、可直接丢给其他 AI 或当 PRD 用的版本。
---
Android AI 编程 Agent App 需求说明

一、项目目标

开发一个 Android 应用（Kotlin + Jetpack Compose），实现一个 AI 编程 Agent，使用户可以通过自然语言直接操作本地 Android 项目代码，实现类似 Cursor 的移动端轻量版本。
---

二、核心使用流程

1. 用户选择本地 Android 项目文件夹（SAF）


2. 在 App 内输入自然语言需求，例如：

“给 MainActivity 加一个按钮”

“修复这个 crash”

“优化这个页面布局”



3. AI 自动：

读取项目结构

分析相关代码文件

生成修改方案（diff/patch）

请求确认后写入文件

更新项目内容

---

三、核心模块设计

1. 文件系统访问层

使用 Storage Access Framework（SAF）

支持用户选择项目文件夹

支持文件读写、创建、删除

使用 DocumentFile API



---

2. 项目结构索引

扫描整个项目目录

构建文件树（Kotlin / XML / Gradle）

支持文件内容读取与缓存

提供基础搜索能力



---

3. AI Agent 核心系统

AI 不直接输出代码，而是通过工具调用：

支持工具：

read_file(path)

write_file(path, content)

list_files(directory)

apply_patch(diff)

search_code(query)


执行流程：

1. 接收用户需求


2. 分析项目上下文


3. 生成修改计划


4. 输出 diff/patch


5. 等待用户确认


6. 执行文件修改




---

4. Diff / Patch 系统

AI 输出统一 diff 格式

App 负责解析 diff 并应用到文件

支持多文件修改

支持修改前后对比

支持回滚



---

5. UI 系统（Compose）

项目文件树浏览

AI 对话输入界面

diff 变更预览界面

文件快速打开与查看

---

6. AI 上下文管理

自动选择相关文件作为上下文

避免发送整个项目

控制 token 使用成本

只传递必要代码片段 + 项目结构



---

四、技术约束

Kotlin + Jetpack Compose
SAF 文件访问机制
不依赖 root 权限
先支持  DeepSeek / 可能会准备mimo
支持流式响应
不运行本地大模型


---

五、系统本质

该应用不是普通 AI 聊天工具，而是：
一个基于工具调用的代码操作型 AI Agent 系统，使 AI 能够在用户授权下读取、分析并修改本地 Android 项目代码。
---

*/