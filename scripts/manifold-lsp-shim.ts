#!/usr/bin/env bun
/**
 * Manifold-aware LSP shim: sits between the omp lsp tool and Eclipse JDT LS (jdtls).
 *
 * jdtls cannot parse Manifold preprocessor directives (#if/#elif/#else/#endif), so this shim:
 *  1. Intercepts textDocument/didOpen|didChange|didSave: reads the REAL file from disk,
 *     evaluates #if branches against symbols from build.properties (the same file
 *     root.gradle generates for the Gradle build), and forwards a filtered "virtual"
 *     document to jdtls. didSave never carries real disk text.
 *  2. Sweeps every module source file with a virtual-text didOpen right after startup,
 *     so JDT full builds compile working copies instead of raw disk content — keeping
 *     diagnostics in one (virtual) coordinate space.
 *  3. Forces full-text sync in the initialize response; disk is the single source of truth.
 *  4. Injects -Pmc_ver=<active> into jdtls's Gradle import so the classpath matches the
 *     active preprocessor version.
 *  5. Remaps positions both directions. Filtering is line-granular, so character offsets
 *     never change — only line numbers are translated.
 *
 * Env:
 *   MANIFOLD_BUILD_PROPERTIES  path to build.properties (default <cwd>/build.properties)
 *   MANIFOLD_LSP_ARGS          space-separated real-server args (default: jdtls launch below)
 */

import { pathToFileURL, fileURLToPath } from "node:url";
import { existsSync, openSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

// ---------------------------------------------------------------------------
// Real server launch (mirrors ~/.omp/agent/lsp.json jdtls entry)
// ---------------------------------------------------------------------------

const DEFAULT_CMD = "python";
const DEFAULT_ARGS = [
    "D:/app/jdtls/bin/jdtls",
    "-data", "D:/tmp/jdtls-omp-workspace",
    "--jvm-arg=-javaagent:D:/app/jdtls/lombok/lombok-1.18.46.jar",
    "--jvm-arg=-Dlog.level=WARNING",
    "--jvm-arg=-Xmx4G",
];

function log(msg: string): void {
    process.stderr.write(`[manifold-lsp-shim] ${msg}\n`);
}

function realServerCmd(): string {
    return process.env.MANIFOLD_LSP_CMD ?? DEFAULT_CMD;
}

function realServerArgs(): string[] {
    if (process.env.MANIFOLD_LSP_ARGS) return process.env.MANIFOLD_LSP_ARGS.split(" ").filter(Boolean);
    return DEFAULT_ARGS;
}

// ---------------------------------------------------------------------------
// JSON value helpers (LSP payloads arrive as untyped JSON)
// ---------------------------------------------------------------------------

interface LspPos { line: number; character: number }
interface LspRange { start: LspPos; end: LspPos }

interface RpcMsg {
    jsonrpc?: string;
    id?: number | string;
    method?: string;
    params?: unknown;
    result?: unknown;
}

interface FrameSink { write(chunk: Buffer | string): unknown }

function isObj(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}
function isPos(v: unknown): v is LspPos {
    return isObj(v) && typeof v.line === "number" && typeof v.character === "number";
}
function isRange(v: unknown): v is LspRange {
    return isObj(v) && isPos(v.start) && isPos(v.end);
}
function asString(v: unknown): string | null {
    return typeof v === "string" ? v : null;
}

// ---------------------------------------------------------------------------
// Preprocessor symbol table (build.properties)
// ---------------------------------------------------------------------------

let cachedSymbols: Map<string, number> | null = null;

function loadSymbols(): Map<string, number> {
    if (cachedSymbols) return cachedSymbols;
    const sym = new Map<string, number>();
    const p = process.env.MANIFOLD_BUILD_PROPERTIES ?? resolve(process.cwd(), "build.properties");
    try {
        for (const line of readFileSync(p, "utf8").split(/\r?\n/)) {
            const t = line.trim();
            if (!t || t.startsWith("#")) continue;
            const m = /^([A-Za-z_][\w.]*)\s*=\s*(\d+)\s*$/.exec(t);
            if (m) sym.set(m[1], Number(m[2]));
        }
    } catch (e) {
        log(`cannot read ${p}: ${e}`);
    }
    cachedSymbols = sym;
    return sym;
}

/** Derive "1.21.11"-style version string: key whose value equals MC_VER's index. */
function activeMcVersion(): string | null {
    const sym = loadSymbols();
    const ver = sym.get("MC_VER");
    if (ver === undefined) return null;
    for (const [k, v] of sym) {
        if (v === ver && k.startsWith("MC_")) return k.slice(3).replace(/_/g, ".");
    }
    return null;
}

// ---------------------------------------------------------------------------
// Expression evaluation: || && == != < <= > >= ! ( ) number ident
// ---------------------------------------------------------------------------

type Tok = { readonly t: "num"; readonly v: number }
         | { readonly t: "id"; readonly v: string }
         | { readonly t: "op"; readonly v: string };

const TOKEN_RE = /\s*(\d+|[A-Za-z_]\w*|<=|>=|==|!=|&&|\|\||[!<>()])/y;

function tokenize(expr: string): Tok[] {
    const toks: Tok[] = [];
    let i = 0;
    while (i < expr.length) {
        TOKEN_RE.lastIndex = i;
        const m = TOKEN_RE.exec(expr);
        if (!m) break;
        i = TOKEN_RE.lastIndex;
        const s = m[1];
        if (/^\d+$/.test(s)) toks.push({ t: "num", v: Number(s) });
        else if (/^[A-Za-z_]/.test(s)) toks.push({ t: "id", v: s });
        else toks.push({ t: "op", v: s });
    }
    return toks;
}

function opIs(tk: Tok | undefined, v: string): boolean {
    return tk !== undefined && tk.t === "op" && tk.v === v;
}

type NumFn = (l: number, r: number) => number;

const REL_OPS: Record<string, NumFn> = {
    "<": (l, r) => (l < r ? 1 : 0),
    ">": (l, r) => (l > r ? 1 : 0),
    "<=": (l, r) => (l <= r ? 1 : 0),
    ">=": (l, r) => (l >= r ? 1 : 0),
};
const EQ_OPS: Record<string, NumFn> = {
    "==": (l, r) => (l === r ? 1 : 0),
    "!=": (l, r) => (l !== r ? 1 : 0),
};
const AND_OP: Record<string, NumFn> = { "&&": (l, r) => (l && r ? 1 : 0) };
const OR_OP: Record<string, NumFn> = { "||": (l, r) => (l || r ? 1 : 0) };

function evalExpr(expr: string, sym: Map<string, number>, defines: Set<string>): boolean {
    const toks = tokenize(expr);
    let pos = 0;

    function binary(next: () => number, ops: Record<string, NumFn>): number {
        let l = next();
        for (;;) {
            const tk = toks[pos];
            if (!tk || tk.t !== "op" || !(tk.v in ops)) break;
            pos++;
            l = ops[tk.v](l, next());
        }
        return l;
    }
    function primary(): number {
        const tk = toks[pos];
        if (!tk) return 0;
        pos++;
        if (tk.t === "num") return tk.v;
        if (tk.t === "id") return defines.has(tk.v) ? 1 : sym.get(tk.v) ?? 0;
        // "(" grouped expression
        const v = or();
        if (opIs(toks[pos], ")")) pos++;
        return v;
    }
    function unary(): number {
        if (opIs(toks[pos], "!")) {
            pos++;
            return unary() ? 0 : 1;
        }
        return primary();
    }
    function rel(): number { return binary(unary, REL_OPS); }
    function eq(): number { return binary(rel, EQ_OPS); }
    function and(): number { return binary(eq, AND_OP); }
    function or(): number { return binary(and, OR_OP); }

    return or() !== 0;
}

// ---------------------------------------------------------------------------
// Line-granular preprocessing
// ---------------------------------------------------------------------------

interface VirtualDoc {
    /** virtual line -> real line */
    readonly v2r: readonly number[];
    /** real line -> virtual line */
    readonly r2v: ReadonlyMap<number, number>;
    readonly text: string;
}

const DIRECTIVE_RE = /^\s*#(if|ifdef|ifndef|elif|else|endif|define|undef)\b\s*(.*)$/;

interface Frame { parentActive: boolean; taken: boolean; active: boolean }

export function preprocess(src: string): VirtualDoc {
    const base = loadSymbols();
    const sym = new Map(base);
    const defines = new Set<string>();
    const lines = src.split(/\r?\n/);
    const out: string[] = [];
    const v2r: number[] = [];
    const stack: Frame[] = [];
    let curActive = true;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const m = DIRECTIVE_RE.exec(line);
        if (m) {
            const kw = m[1];
            const rest = (m[2] ?? "").trim().replace(/\/\/.*$/, "").trim();
            switch (kw) {
                case "if":
                case "ifdef":
                case "ifndef": {
                    stack.push({ parentActive: curActive, taken: false, active: false });
                    const top = stack[stack.length - 1];
                    let cond = false;
                    if (top.parentActive) {
                        if (kw === "if") cond = evalExpr(rest, sym, defines);
                        else if (kw === "ifdef") cond = defines.has(rest) || sym.has(rest);
                        else cond = !(defines.has(rest) || sym.has(rest));
                    }
                    top.taken = cond;
                    top.active = cond;
                    curActive = cond;
                    break;
                }
                case "elif": {
                    const top = stack[stack.length - 1];
                    if (!top) break;
                    let cond = false;
                    if (top.parentActive && !top.taken) cond = evalExpr(rest, sym, defines);
                    top.taken = top.taken || cond;
                    top.active = cond;
                    curActive = cond;
                    break;
                }
                case "else": {
                    const top = stack[stack.length - 1];
                    if (!top) break;
                    top.active = top.parentActive && !top.taken;
                    top.taken = true;
                    curActive = top.active;
                    break;
                }
                case "endif": {
                    const top = stack.pop();
                    curActive = top ? top.parentActive : true;
                    break;
                }
                case "define": if (curActive) defines.add(rest); break;
                case "undef": if (curActive) defines.delete(rest); break;
            }
            continue; // directive lines never reach the virtual document
        }
        if (curActive) {
            out.push(line);
            v2r.push(i);
        }
    }

    const r2v = new Map<number, number>();
    for (let v = 0; v < v2r.length; v++) r2v.set(v2r[v], v);
    return { v2r, r2v, text: out.join("\n") };
}

// ---------------------------------------------------------------------------
// Document map store (lazy, disk-backed)
// ---------------------------------------------------------------------------

class DocMaps {
    private cache = new Map<string, VirtualDoc>();

    get(uri: string): VirtualDoc | null {
        if (!uri.startsWith("file:") || !uri.endsWith(".java")) return null;
        return this.cache.get(uri) ?? this.refresh(uri);
    }

    refresh(uri: string): VirtualDoc | null {
        if (!uri.startsWith("file:") || !uri.endsWith(".java")) return null;
        let p: string;
        try {
            p = fileURLToPath(uri);
        } catch {
            return null;
        }
        if (!existsSync(p)) return null;
        const doc = preprocess(readFileSync(p, "utf8"));
        this.cache.set(uri, doc);
        return doc;
    }
}

const docs = new DocMaps();

/** Force-disable Gradle import (generated Eclipse projects replace it) and dump what arrived. */
function injectGradleVersionSettings(params: unknown): unknown {
    try {
        require("node:fs").appendFileSync(
            process.env.MANIFOLD_LSP_STDERR_LOG ?? "D:/tmp/manifold-lsp-jdtls.log",
            `[shim-debug] didChangeConfiguration settings: ${JSON.stringify(params)}\n`);
    } catch {}
    const settings = isObj(params) && isObj(params.java) ? { ...params.java } : {};
    const imp = isObj(settings.import) ? { ...settings.import } : {};
    imp.gradle = { ...(isObj(imp.gradle) ? imp.gradle : {}), enabled: false };
    const out: Record<string, unknown> = isObj(params) ? { ...params } : {};
    out.java = { ...settings, import: imp };
    return out;
}
// ---------------------------------------------------------------------------
// Workspace sweep: pre-open every module source file with virtual text so JDT
// builds compile working copies instead of raw disk content.
// ---------------------------------------------------------------------------

const MODULE_GLOBS = ["{common,fabric,forge,neoforge}/src/**/*.java"];

async function collectSourceFiles(): Promise<string[]> {
    const out: string[] = [];
    for (const pattern of MODULE_GLOBS) {
        const glob = new Bun.Glob(pattern);
        for await (const rel of glob.scan({ cwd: process.cwd(), onlyFiles: true })) {
            out.push(rel);
        }
    }
    return out;
}

async function sweepOpenDocuments(sink: FrameSink): Promise<number> {
    const files = await collectSourceFiles();
    if (files.length > 2000) {
        log(`source tree too large (${files.length}), sweep skipped`);
        return 0;
    }
    let opened = 0;
    for (const rel of files) {
        const uri = pathToFileURL(resolve(process.cwd(), rel)).href;
        const doc = docs.refresh(uri);
        if (!doc) continue;
        writeFrame(sink, {
            jsonrpc: "2.0",
            method: "textDocument/didOpen",
            params: { textDocument: { uri, languageId: "java", version: 1, text: doc.text } },
        });
        opened++;
    }
    log(`sweep opened ${opened}/${files.length} documents`);
    return opened;
}

// ---------------------------------------------------------------------------
// Position mapping
// ---------------------------------------------------------------------------

function realToVirtual(d: VirtualDoc, p: LspPos): LspPos {
    const v = d.r2v.get(p.line);
    if (v !== undefined) return { line: v, character: p.character };
    // Real line inside a removed region: clamp to nearest preceding virtual line.
    let lo = 0;
    let hi = d.v2r.length - 1;
    if (hi < 0) return { line: 0, character: p.character };
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (d.v2r[mid] <= p.line) lo = mid;
        else hi = mid - 1;
    }
    return { line: lo, character: p.character };
}

function virtualToReal(d: VirtualDoc, p: LspPos): LspPos {
    const r = d.v2r[p.line];
    if (r !== undefined) return { line: r, character: p.character };
    const last = d.v2r.length - 1;
    return { line: last >= 0 ? d.v2r[last] : 0, character: p.character };
}

/** forward=true maps real->virtual; forward=false maps virtual->real. */
function mapRange(d: VirtualDoc, r: LspRange, forward: boolean): LspRange {
    const f = forward ? realToVirtual : virtualToReal;
    return { start: f(d, r.start), end: f(d, r.end) };
}

// ---------------------------------------------------------------------------
// JSON-RPC framing over stdio
// ---------------------------------------------------------------------------

class Framing {
    private buf = Buffer.alloc(0);

    push(chunk: Buffer): RpcMsg[] {
        this.buf = Buffer.concat([this.buf, chunk]);
        const msgs: RpcMsg[] = [];
        for (;;) {
            const headerEnd = this.buf.indexOf("\r\n\r\n");
            if (headerEnd < 0) break;
            const header = this.buf.subarray(0, headerEnd).toString("utf8");
            const cm = /Content-Length:\s*(\d+)/i.exec(header);
            if (!cm) {
                this.buf = this.buf.subarray(headerEnd + 4); // resync past malformed header
                continue;
            }
            const len = Number(cm[1]);
            const bodyStart = headerEnd + 4;
            if (this.buf.length < bodyStart + len) break;
            const body = this.buf.subarray(bodyStart, bodyStart + len).toString("utf8");
            this.buf = this.buf.subarray(bodyStart + len);
            try {
                msgs.push(JSON.parse(body) as RpcMsg);
            } catch (e) {
                log(`bad JSON dropped: ${e}`);
            }
        }
        return msgs;
    }
}

function writeFrame(sink: FrameSink, msg: RpcMsg): void {
    const body = Buffer.from(JSON.stringify(msg), "utf8");
    sink.write(`Content-Length: ${body.length}\r\n\r\n`);
    sink.write(body);
}

// ---------------------------------------------------------------------------
// Message transformation
// ---------------------------------------------------------------------------

/** Methods whose params carry positions/ranges inside a text document. */
const POSITION_PARAM_METHODS: Record<string, true> = {
    "textDocument/definition": true,
    "textDocument/typeDefinition": true,
    "textDocument/implementation": true,
    "textDocument/references": true,
    "textDocument/hover": true,
    "textDocument/rename": true,
    "textDocument/prepareRename": true,
    "textDocument/codeAction": true,
    "textDocument/completion": true,
    "textDocument/signatureHelp": true,
    "textDocument/documentHighlight": true,
    "textDocument/selectionRange": true,
};

/** Merge -Pmc_ver=<active> into initializationOptions.settings.java.import.gradle.arguments. */
function injectGradleVersionArgs(params: unknown): unknown {
    try {
        const init = isObj(params) ? params.initializationOptions : undefined;
        require("node:fs").appendFileSync(
            process.env.MANIFOLD_LSP_STDERR_LOG ?? "D:/tmp/manifold-lsp-jdtls.log",
            `[shim-debug] initializationOptions: ${JSON.stringify(init)}\n` +
            `[shim-debug] top-level keys: ${isObj(params) ? Object.keys(params).join(",") : "?"}\n`);
    } catch {}
    const ver = activeMcVersion();
    if (!ver || !isObj(params)) return params;
    log(`injecting java.import.gradle.arguments=-Pmc_ver=${ver}`);
    const init = isObj(params.initializationOptions) ? params.initializationOptions : {};
    const settings = isObj(init.settings) ? init.settings : {};
    const java = isObj(settings.java) ? settings.java : {};
    const imp = isObj(java.import) ? { ...java.import } : {};
    imp.gradle = { ...(isObj(imp.gradle) ? imp.gradle : {}), arguments: [`-Pmc_ver=${ver}`] };
    return {
        ...params,
        initializationOptions: {
            ...init,
            settings: { ...settings, java: { ...java, import: imp } },
        },
    };
}

function remapIncoming(method: string, params: unknown): unknown {
    if (!isObj(params)) return params;

    if (method === "textDocument/didOpen") {
        const td = params.textDocument;
        const uri = isObj(td) ? asString(td.uri) : null;
        const doc = uri ? docs.refresh(uri) : null;
        if (doc && isObj(td)) return { ...params, textDocument: { ...td, text: doc.text } };
        return params;
    }
    if (method === "textDocument/didChange") {
        const td = params.textDocument;
        const uri = isObj(td) ? asString(td.uri) : null;
        const doc = uri ? docs.refresh(uri) : null;
        if (doc && isObj(td)) {
            // Full-text sync was forced via the initialize response.
            return { ...params, contentChanges: [{ text: doc.text }] };
        }
        return params;
    }
    if (method === "textDocument/didSave") {
        // Never let real disk text through — it would replace the virtual buffer.
        const td = isObj(params.textDocument) ? params.textDocument : undefined;
        return td ? { textDocument: td } : params;
    }
    if (method === "textDocument/didClose") {
        // Keep the cache; the sweep still owns this document's virtual view.
        return params;
    }

    if (POSITION_PARAM_METHODS[method]) {
        const uri = isObj(params.textDocument) ? asString(params.textDocument.uri) : null;
        const d = uri ? docs.get(uri) : null;
        if (d) {
            const p: Record<string, unknown> = { ...params };
            if (isPos(p.position)) p.position = realToVirtual(d, p.position);
            if (isRange(p.range)) p.range = mapRange(d, p.range, true);
            if (Array.isArray(p.ranges)) {
                p.ranges = p.ranges.map((r) => (isRange(r) ? mapRange(d, r, true) : r));
            }
            if (isObj(p.context) && Array.isArray(p.context.diagnostics)) {
                p.context = {
                    ...p.context,
                    diagnostics: p.context.diagnostics.map((dg) =>
                        isObj(dg) && isRange(dg.range) ? { ...dg, range: mapRange(d, dg.range, true) } : dg),
                };
            }
            return p;
        }
    }
    return params;
}

/**
 * Deep transform of a server->client payload:
 *  - objects carrying their own uri/targetUri get their ranges mapped against that document
 *  - WorkspaceEdit.changes / documentChanges get edit ranges mapped per document
 *  - any remaining bare `range` field is mapped against defaultDoc when provided
 *    (responses belonging to a request on a known java document)
 */
function remapTree(node: unknown, defaultDoc: VirtualDoc | null): unknown {
    if (Array.isArray(node)) return node.map((n) => remapTree(n, defaultDoc));
    if (!isObj(node)) return node;

    const selfUri = asString(node.uri) ?? asString(node.targetUri);
    if (selfUri) {
        const d = docs.get(selfUri);
        if (d) {
            const out: Record<string, unknown> = { ...node };
            if (isRange(out.range)) out.range = mapRange(d, out.range, false);
            if (isRange(out.targetRange)) out.targetRange = mapRange(d, out.targetRange, false);
            if (isRange(out.targetSelectionRange)) {
                out.targetSelectionRange = mapRange(d, out.targetSelectionRange, false);
            }
            return out;
        }
    }

    if (isObj(node.changes)) {
        const changes: Record<string, unknown> = {};
        for (const [uri, edits] of Object.entries(node.changes)) {
            const d = docs.get(uri);
            changes[uri] = d && Array.isArray(edits)
                ? edits.map((e) => (isObj(e) && isRange(e.range) ? { ...e, range: mapRange(d, e.range, false) } : e))
                : edits;
        }
        return { ...node, changes };
    }

    if (Array.isArray(node.documentChanges)) {
        return {
            ...node,
            documentChanges: node.documentChanges.map((dc) => {
                const uri = isObj(dc) && isObj(dc.textDocument) ? asString(dc.textDocument.uri) : null;
                const d = uri ? docs.get(uri) : null;
                if (!d || !isObj(dc) || !Array.isArray(dc.edits)) return dc;
                return {
                    ...dc,
                    edits: dc.edits.map((e) =>
                        isObj(e) && isRange(e.range) ? { ...e, range: mapRange(d, e.range, false) } : e),
                };
            }),
        };
    }

    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node)) {
        if (k === "range" && defaultDoc && isRange(v)) {
            out[k] = mapRange(defaultDoc, v, false);
        } else {
            out[k] = remapTree(v, defaultDoc);
        }
    }
    return out;
}

function remapPublishDiagnostics(params: unknown): unknown {
    if (!isObj(params)) return params;
    const uri = asString(params.uri);
    const d = uri ? docs.get(uri) : null;
    if (!d) return params;
    const diags = Array.isArray(params.diagnostics) ? params.diagnostics : [];
    const mapped: unknown[] = [];
    for (const dg of diags) {
        if (isObj(dg) && isRange(dg.range)) {
            mapped.push({ ...dg, range: mapRange(d, dg.range, false) });
        }
        // Diagnostics without a range are dropped.
    }
    return { ...params, diagnostics: mapped };
}

/** Force full-text sync so didChange payloads are always complete documents. */
function forceFullSync(result: unknown): unknown {
    if (!isObj(result) || !isObj(result.capabilities)) return result;
    const sync = result.capabilities.textDocumentSync;
    if (typeof sync === "number") {
        return { ...result, capabilities: { ...result.capabilities, textDocumentSync: 1 } };
    }
    if (isObj(sync)) {
        return { ...result, capabilities: { ...result.capabilities, textDocumentSync: { ...sync, change: 1 } } };
    }
    return result;
}

// ---------------------------------------------------------------------------
// Main pump
// ---------------------------------------------------------------------------
async function main(): Promise<void> {
    const errLog = openSync(process.env.MANIFOLD_LSP_STDERR_LOG ?? "D:/tmp/manifold-lsp-jdtls.log", "a");
    const proc = Bun.spawn([realServerCmd(), ...realServerArgs()], {
        stdin: "pipe",
        stdout: "pipe",
        stderr: errLog,
    });
    log(`backend spawned pid=${proc.pid}`);

    /** Outgoing request id -> target document uri, for response range remapping. */
    let swept = false;
    /** Debounce handle for post-import re-sweeps; only tested for presence. */
    let resweepTimer: unknown;
    const pendingRequests = new Map<number | string, string | undefined>();

    proc.exited.then((code) => {
        log(`backend exited (${code})`);
        process.exit(code ?? 0);
    });
    process.stdin.on("end", () => {
        proc.kill();
        process.exit(0);
    });

    // Backend -> client
    void (async () => {
        const framing = new Framing();
        for await (const chunk of proc.stdout) {
            for (const msg of framing.push(chunk as Buffer)) {
                let out = msg;
                if (msg.method === "textDocument/publishDiagnostics") {
                    out = { ...msg, params: remapPublishDiagnostics(msg.params) };
                } else if (msg.id !== undefined && msg.method === undefined) {
                    const reqUri = pendingRequests.get(msg.id);
                    pendingRequests.delete(msg.id);
                    const d = reqUri ? docs.get(reqUri) : null;
                    if (msg.result !== undefined) {
                        out = { ...msg, result: remapTree(forceFullSync(msg.result), d) };
                    }
                    // First backend capability exchange completed -> sweep sources.
                    if (!swept && msg.result !== undefined) {
                        swept = true;
                        void sweepOpenDocuments(proc.stdin).catch((e) => log(`sweep failed: ${e}`));
                    }
                } else if (msg.method === "$/progress") {
                    // Project import emits progress-end events; buffers opened before
                    // import completes attach to no project, so re-sweep (debounced).
                    const p = msg.params;
                    if (isObj(p) && p.kind === "end" && !resweepTimer) {
                        resweepTimer = setTimeout(() => {
                            resweepTimer = undefined;
                            void sweepOpenDocuments(proc.stdin).catch((e) => log(`re-sweep failed: ${e}`));
                        }, 2000);
                    }
                 }
                writeFrame(process.stdout, out);
            }
        }
    })().catch((e) => {
        log(`backend reader failed: ${e}`);
        process.exit(1);
    });

    // Client -> backend
    const framing = new Framing();
    process.stdin.on("data", (chunk: Buffer) => {
        for (const msg of framing.push(chunk)) {
            let out = msg;
            if (msg.method) {
                let params = msg.params;
                if (msg.method === "initialize") params = injectGradleVersionArgs(params);
                if (msg.method === "workspace/didChangeConfiguration") {
                    params = {
                        settings: injectGradleVersionSettings(isObj(params) ? params.settings : params),
                    };
                }
                out = { ...msg, params: remapIncoming(msg.method, params) };
                if (msg.id !== undefined && POSITION_PARAM_METHODS[msg.method]) {
                    const td = isObj(out.params) && isObj(out.params.textDocument) ? out.params.textDocument : null;
                    pendingRequests.set(msg.id, td ? asString(td.uri) ?? undefined : undefined);
                }
            }
            try {
                writeFrame(proc.stdin, out);
            } catch (e) {
                log(`write to backend failed: ${e}`);
                process.exit(1);
            }
        }
    });
}

main().catch((e) => {
    log(`fatal: ${e}`);
    process.exit(1);
});
