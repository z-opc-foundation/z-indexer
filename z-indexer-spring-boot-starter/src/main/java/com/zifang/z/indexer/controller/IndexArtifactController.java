package com.zifang.z.indexer.controller;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.indexer.pipeline.IndexBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

/**
 * master 纯内容制品下载端点：构建 SUCCESS 后 {@link com.zifang.z.indexer.pipeline.ArtifactService}
 * 自动维护 {@code artifacts/{repoCode}.zip}，此处透出给需要整仓纯内容快照的消费方
 * （cp 单文件 zip + 解压，替代整仓 cp，解决大仓 NAS→NAS 拷贝分钟级耗时）。
 *
 * <p>鉴权与既有 /index/query 系列一致（集群内网直连）。404 表示制品尚未生成
 * （仓从未构建成功过 / 打包进行中），消费方应回退整卷 cp 路径。
 */
@RestController
@RequestMapping("/index")
public class IndexArtifactController {

    private static final Logger log = LoggerFactory.getLogger(IndexArtifactController.class);

    @Autowired
    private IndexBuildService buildService;

    @RequestMapping(value = "/repo/artifact", method = RequestMethod.GET)
    public ResponseEntity<FileSystemResource> artifact(@RequestParam("repoCode") String repoCode) {
        // repoCode 白名单校验（防路径穿越；仓库名约定为字母数字与 ._- 组合）
        if (repoCode == null || !repoCode.matches("[A-Za-z0-9._-]{1,120}")) {
            return ResponseEntity.badRequest().build();
        }
        File zip = buildService.getArtifactService().artifactFile(repoCode);
        if (!zip.isFile()) {
            return ResponseEntity.status(404).build();
        }
        String commit = "";
        try {
            File meta = buildService.getArtifactService().metaFile(repoCode);
            if (meta.isFile()) {
                JsonObject o = JsonUtil.parseObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                commit = o.getString("commit");
            }
        } catch (Exception e) {
            log.warn("[Artifact] meta read failed for {}: {}", repoCode, e.getMessage());
        }
        return ResponseEntity.ok()
                .header("X-Artifact-Commit", commit == null ? "" : commit)
                .header("Content-Disposition", "attachment; filename=\"" + repoCode + ".zip\"")
                .contentLength(zip.length())
                .body(new FileSystemResource(zip));
    }
}
