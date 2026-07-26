# Multi-channel Data Plane Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 1.20.1 Fabric 已通过的数据面多通道推广到 Fabric+NeoForge × 九段锚点，session token 经握手尾部下发，全矩阵 DataplanePhase PASS。

**Architecture:** common sessionToken + ClientLifecycle → Fabric 握手尾部/时序 → NeoForge 同构 → 全矩阵冒烟。

**Tech Stack:** Java 17, Netty, Manifold, JUnit, PowerShell smoke

## Global Constraints

- 仅 Fabric + NeoForge；不碰 Forge
- 不 bump protocolVersion；握手尾部 append + isReadable
- tryRouteBulk false → Primary
- 禁止关功能换绿；禁止覆盖 1.20.1 fabric 已验证路径

## Tasks

1. common: sessionToken + ClientBundle API + Lifecycle + HandshakeTail
2. fabric: handshake tail + JOIN timing
3. neoforge: ChunkSender + handshake all segments + client lifecycle
4. compile anchors + DataplanePhase full matrix

Inline execution (user authorized unattended).
