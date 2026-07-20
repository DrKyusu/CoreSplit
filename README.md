## CoreSplit — Minecraft 26.2 Fabric 并行引擎 Mod
# 核心功能
核心思想借鉴了 C2ME 的线程池调度 
、Async 的实体多线程 
、DimensionalThreading 的维度隔离 
 以及 Sodium 的生产者-消费者并行模型 
总结： 将游戏进程拆解为多阶段流水线，分配至不同 CPU 核心执行。
