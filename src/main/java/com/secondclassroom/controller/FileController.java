package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AttachmentUploadResult;
import com.secondclassroom.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;

/**
 * 附件接口。
 * - POST /api/upload              上传文件（multipart/form-data，字段名 file）—— 路径严格按设计文档 §6
 * - GET  /api/files/{attachmentId} 下载文件
 *
 * ⚠️ 两个接口的路径前缀不一样（`/api/upload` vs `/api/files/...`），这是**文档就这么定的**
 *    —— 上传接口在文档第 6 章独立成章、路径是 `/api/upload`，所以这里不能用类级 @RequestMapping
 *    统一前缀，只能各自写全路径。
 *
 * ⚠️ 下载接口**文档里没有**（文档假设文件在 OSS 上、直接给 URL 就完了）。因为本项目存本地磁盘，
 *    必须有个接口把文件吐出来，所以这是本地存储带来的**必要增补**。
 *
 * ⚠️ 下载接口也是项目里**唯一一个成功时不返回 Result 的接口**（响应体就是文件字节流），
 *    但下载失败时仍然走项目约定，返回 Result 结构的 JSON，前端按 code 判断即可。
 */
@RestController
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 上传（文档 §6：POST /api/upload）。
     * 上传者从 Token 取（JwtInterceptor 放进 request 的 userId）。
     * 返回体里的 attachmentId 就是之后要放进业务请求 attachmentIds 数组的东西。
     */
    @PostMapping("/api/upload")
    public Result<AttachmentUploadResult> upload(HttpServletRequest request,
                                                @RequestParam("file") MultipartFile file) {
        String userId = (String) request.getAttribute("userId");
        return fileStorageService.upload(userId, file);
    }

    /** 下载。鉴权见 FileStorageService「下载鉴权」：无权访问返回 404。 */
    @GetMapping("/api/files/{attachmentId}")
    public ResponseEntity<Object> download(HttpServletRequest request,
                                          @PathVariable String attachmentId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");

        Result<FileStorageService.DownloadFile> result =
                fileStorageService.download(userId, userType, attachmentId);

        if (result.getCode() != 0) {
            // 失败：按项目约定返回 Result（HTTP 200，真正的错在 body 的 code 里）
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
        }

        FileStorageService.DownloadFile file = result.getData();
        // ContentDisposition.attachment() 会按 RFC 5987 处理中文文件名，避免乱码
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.resource());
    }
}
