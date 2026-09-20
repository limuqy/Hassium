package hassium.publish

import groovy.json.JsonSlurper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 发布平台的「是否已存在」查询，供幂等上传使用。
 *
 * 只发 GET，不做任何写操作。返回三态字符串（不含空格，便于脚本按 token 解析）：
 *   yes              —— 已存在
 *   no               —— 不存在
 *   no-token         —— 缺凭据，无法查询（仅 CurseForge：API 强制要求 x-api-key）
 *   unknown(<原因>)  —— 查询失败：HTTP 4xx/5xx 或网络异常
 *
 * 为什么不把 unknown 当 no：本机访问 CurseForge API 会稳定吃到 Cloudflare managed
 * challenge（403，见 .github/workflows/publish-mods.yml 注释），把它当 no 会让幂等
 * 静默失效。调用方应当「只有 yes 才跳过上传」，unknown 时照常上传并给出告警。
 *
 * 包名注意：目录名不能叫 build（.gitignore 的 `build` 规则会把整个目录吞掉，
 * 导致文件永远进不了仓库、CI 编译 buildSrc 失败）。故用 hassium.publish。
 */
class PublishState {

    static final String YES = 'yes'
    static final String NO = 'no'
    static final String NO_TOKEN = 'no-token'

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private static final String USER_AGENT = 'hassium-publish/1.0 (github.com/limuqy/hassium)'

    /** @return [status: 'ok'|'missing'|'unknown', body: String?, reason: String?] */
    private static Map request(String url, Map<String, String> headers) {
        try {
            def builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header('Accept', 'application/json')
                    .header('User-Agent', USER_AGENT)
            headers.each { k, v -> builder.header(k, v) }
            def resp = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 404) {
                return [status: 'missing']
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return [status: 'unknown', reason: "${resp.statusCode()}"]
            }
            return [status: 'ok', body: resp.body()]
        } catch (Exception e) {
            return [status: 'unknown', reason: e.class.simpleName]
        }
    }

    /** CurseForge：按上传文件名判断该项目下是否已有同名文件。 */
    static String curseforgeFileState(String token, String projectId, String fileName) {
        if (!token) {
            return NO_TOKEN
        }
        if (!projectId) {
            return 'unknown(no-project-id)'
        }
        def slurper = new JsonSlurper()
        def pageSize = 50
        def index = 0
        while (index < 1000) {   // 最多 20 页，防呆
            def r = request(
                    "https://api.curseforge.com/v1/mods/${projectId}/files?index=${index}&pageSize=${pageSize}",
                    ['x-api-key': token])
            if (r.status == 'unknown') {
                return "unknown(${r.reason})"
            }
            if (r.status == 'missing') {
                return NO        // 项目不存在 → 交给上传阶段报错
            }
            def root = slurper.parseText(r.body as String)
            if ((root.data ?: []).any { it.fileName == fileName }) {
                return YES
            }
            def total = (root.pagination?.totalCount ?: 0) as int
            index += pageSize
            if (index >= total) {
                return NO
            }
        }
        return NO
    }

    /** Modrinth：按 version_number 判断该项目下是否已有该版本（Modrinth 要求同项目内唯一）。 */
    static String modrinthVersionState(String token, String projectId, String versionNumber) {
        if (!projectId) {
            return 'unknown(no-project-id)'
        }
        def headers = [:]
        if (token) {
            headers['Authorization'] = token
        }
        def r = request("https://api.modrinth.com/v2/project/${projectId}/version", headers)
        if (r.status == 'unknown') {
            return "unknown(${r.reason})"
        }
        if (r.status == 'missing') {
            return NO            // 项目不存在（或未公开且无 token）→ 交给上传阶段报错
        }
        def versions = new JsonSlurper().parseText(r.body as String)
        return versions.any { it.version_number == versionNumber } ? YES : NO
    }
}
