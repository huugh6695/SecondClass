package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AttachmentUploadResult;
import com.secondclassroom.util.DailySeqId;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件：上传落盘 / 下载取回。接口规格对齐设计文档 §6「文件上传接口」+ §1.12「附件生命周期」。
 *
 * <h3>生命周期（两步走，别混）</h3>
 * <ol>
 *   <li><b>上传</b>（{@code POST /api/upload}）：文件存到本地磁盘，t_attachment 插一条
 *       <b>status='TEMP'</b> 的记录，此时它不属于任何工单/消息（target_id / target_type 都是 NULL）。</li>
 *   <li><b>绑定</b>：提交工单 / 回复 / 追问 / 再次咨询时，把 attachmentId 放进请求体的
 *       {@code attachmentIds}，那条记录才会变成 <b>status='BOUND'</b>，并写上 target（绑定逻辑在 WorkOrderService）。</li>
 * </ol>
 * 所以上传接口本身只负责"把文件收下来 + 登记"，不关心它以后属于谁。
 *
 * <h3>上传校验顺序（按文档 §6「后端处理」）</h3>
 * 扩展名白名单 → Magic Number（防改后缀伪装）→ 大小上限 → 图片尺寸。
 *
 * <h3>文件存哪</h3>
 * 表里 {@code file_url} 的注释写的是 "OSS URL"，文档也假设传 OSS（路径 {@code work-order/{yyyyMMdd}/{uuid}.jpg}），
 * 但本项目没接对象存储，所以落到本地磁盘：
 * <ul>
 *   <li>磁盘位置：{@code {app.upload.dir}/{yyyyMMdd}/{attachmentId}{后缀}}，
 *       例如 {@code ./uploads/20260926/ATT20260926000001.png}。
 *       这个路径**可以从 attachment_id + 原文件名后缀直接推出来**，所以不用在库里另存路径。</li>
 *   <li>{@code file_url} 存**下载接口地址** {@code /api/files/{attachmentId}} —— 它仍然是
 *       "能取到文件的 URL"，符合列的语义；将来换成真 OSS 只需改这一处。</li>
 * </ul>
 *
 * <h3>下载鉴权</h3>
 * 附件是私密的，不能谁拿到 attachmentId 就能下。规则：
 * <ul>
 *   <li>TEMP（还没绑定）→ 只有上传者本人能下</li>
 *   <li>BOUND 到工单 → 得能看这张工单（提交人 / 负责人 / ADMIN）</li>
 *   <li>BOUND 到消息 → 找到消息所属工单，同样按工单可见性判断</li>
 * </ul>
 * 无权访问一律返回 **404 而不是 403** —— 不暴露"这个附件存在，只是不属于你"（和项目里其他资源一致）。
 *
 * <h3>与文档的两处差异（本地存储带来的必要增补，已与用户确认）</h3>
 * <ol>
 *   <li>文档没有"下载接口"（它假设文件在 OSS 上直接给 URL）。我们存本地，必须有
 *       {@code GET /api/files/{id}} 才能把文件吐出来 —— 这是必要的增补。</li>
 *   <li>文档的文档示例把 attachmentId 写成自增数字 {@code 1}，但 schema.sql 明确是
 *       {@code VARCHAR(32)} 的 {@code ATT+YYYYMMDD+6位序号} —— **以 schema 为准**。</li>
 * </ol>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 附件编号前缀，格式 ATT + yyyyMMdd + 6 位序号（与 t_attachment 注释一致） */
    private static final String ID_PREFIX = "ATT";
    private static final int ID_SEQ_WIDTH = 6;

    /** 文档 §6：前端已把图片压到长边 ≤1920px，后端做二次校验，超了就拒。 */
    private static final int MAX_IMAGE_EDGE = 1920;

    /** 能读到尺寸的图片后缀（用于尺寸二次校验）。webp 不在 ImageIO 标准支持里，跳过校验。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private final JdbcTemplate jdbcTemplate;
    /** 生成附件号（ATT + yyyyMMdd + 6 位序号）用；见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;
    private final Path rootDir;
    private final long maxFileSize;
    private final Set<String> allowedExtensions;

    public FileStorageService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redis,
            @Value("${app.upload.dir:./uploads}") String uploadDir,
            @Value("${app.upload.max-file-size:5242880}") long maxFileSize,
            @Value("${app.upload.allowed-extensions:jpg,jpeg,png,gif,bmp,webp,pdf,doc,docx,xls,xlsx,ppt,pptx,txt,zip,rar}")
            String allowedExtensions) {

        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        // toAbsolutePath + normalize：避免配置里写相对路径时，存/取算出来的绝对路径不一致
        this.rootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.allowedExtensions = Arrays.stream(allowedExtensions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                // LinkedHashSet：保留配置里的书写顺序，报错时列出来的允许后缀才是稳定的（Set 是无序的）
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 启动时把上传根目录建好，省得第一次上传才发现目录不存在。 */
    @PostConstruct
    void init() {
        try {
            Files.createDirectories(rootDir);
            log.info("附件存储目录：{}（单文件上限 {} MB，允许后缀 {}）",
                    rootDir, maxFileSize / 1024 / 1024, allowedExtensions);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建附件存储目录：" + rootDir, e);
        }
    }

    // ============================================================
    // 上传
    // ============================================================

    public Result<AttachmentUploadResult> upload(String userId, MultipartFile file) {
        // 1) 文件不能为空
        if (file == null || file.isEmpty()) {
            return Result.fail(400, "请选择要上传的文件");
        }

        // 2) 文件名清洗：只取最后一段，挡掉 "../" 这种路径穿越
        String originalName = sanitizeFileName(file.getOriginalFilename());
        if (originalName.isEmpty()) {
            return Result.fail(400, "文件名不合法");
        }
        if (originalName.length() > 255) {
            originalName = originalName.substring(originalName.length() - 255);   // 列宽 VARCHAR(255)
        }

        // 3) 后缀白名单（文档 §6 第 2 步）
        String ext = extensionOf(originalName);
        if (ext.isEmpty()) {
            return Result.fail(400, "文件必须有扩展名");
        }
        if (!allowedExtensions.contains(ext)) {
            return Result.fail(400, "不支持的文件类型：." + ext + "（允许：" + String.join(" / ", allowedExtensions) + "）");
        }

        // 4) Magic Number 校验（文档 §6 第 3 步）：防止把 exe 改名成 jpg 蒙混过关
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传内容失败：{}", originalName, e);
            return Result.fail(500, "文件读取失败，请稍后重试");
        }
        if (!magicMatches(ext, content)) {
            return Result.fail(400, "文件内容与扩展名不符（." + ext + "），请上传真实的文件");
        }

        // 5) 大小上限（文档 §6 第 4 步）。multipart 层还有一道更早的网，这里是业务层兜底。
        if (file.getSize() > maxFileSize) {
            return Result.fail(400, "文件超过大小限制（最大 " + (maxFileSize / 1024 / 1024) + " MB）");
        }

        // 6) 图片尺寸二次校验（文档 §6 第 5 步）：长边不能超过 1920px
        if (IMAGE_EXTENSIONS.contains(ext)) {
            int maxEdge = readMaxImageEdge(content);
            if (maxEdge > MAX_IMAGE_EDGE) {
                return Result.fail(400, "图片尺寸过大（长边 " + maxEdge + "px，上限 " + MAX_IMAGE_EDGE
                        + "px），请压缩后再上传");
            }
        }

        // 7) 生成编号 -> 算出落盘路径 -> 写文件
        String attachmentId = DailySeqId.next(jdbcTemplate, redis, ID_PREFIX, "t_attachment", "attachment_id", ID_SEQ_WIDTH);
        Path target = resolvePath(attachmentId, ext);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(new ByteArrayInputStream(content), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("附件写盘失败 attachmentId={} 路径={}", attachmentId, target, e);
            return Result.fail(500, "文件保存失败，请稍后重试");
        }

        // 8) 登记到 t_attachment（status 默认 TEMP，target 留空）
        String fileUrl = "/api/files/" + attachmentId;
        String fileType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (fileType.length() > 50) {
            fileType = fileType.substring(0, 50);   // 列宽 VARCHAR(50)
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_attachment "
                            + "(attachment_id, upload_user_id, file_name, file_url, file_type, file_size, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'TEMP')",
                    attachmentId, userId, originalName, fileUrl, fileType, file.getSize());
        } catch (RuntimeException e) {
            // 登记失败就把刚写下的文件删掉，别留孤儿文件占地方
            deleteQuietly(target);
            throw e;
        }

        log.info("附件上传成功 attachmentId={} 上传者={} 文件名={} 大小={}B",
                attachmentId, userId, originalName, file.getSize());
        // 9) 返回（字段严格按文档 §6：attachmentId / fileUrl / fileName / fileSize）
        return Result.success("上传成功",
                new AttachmentUploadResult(attachmentId, fileUrl, originalName, file.getSize()));
    }

    // ============================================================
    // 下载
    // ============================================================

    /** 下载接口需要的东西：文件本体 + 原始文件名（用于 Content-Disposition）+ MIME。 */
    public record DownloadFile(Resource resource, String fileName, String contentType) { }

    public Result<DownloadFile> download(String userId, String userType, String attachmentId) {
        AttachmentRow row = findAttachment(attachmentId);
        if (row == null) {
            return Result.fail(404, "附件不存在");
        }
        // 无权访问也返回 404：不暴露"存在但不属于你"
        if (!canAccess(userId, userType, row)) {
            return Result.fail(404, "附件不存在");
        }

        Path path = resolvePath(attachmentId, extensionOf(row.fileName()));
        if (!Files.exists(path)) {
            log.warn("附件记录存在但磁盘上找不到文件：attachmentId={} 路径={}", attachmentId, path);
            return Result.fail(404, "附件文件已丢失");
        }

        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.isReadable()) {
                return Result.fail(500, "文件读取失败");
            }
            String contentType = row.fileType() == null ? "application/octet-stream" : row.fileType();
            return Result.success("success", new DownloadFile(resource, row.fileName(), contentType));
        } catch (Exception e) {
            log.error("附件读取异常 attachmentId={}", attachmentId, e);
            return Result.fail(500, "文件读取失败");
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private record AttachmentRow(String attachmentId, String uploadUserId, String targetType,
                                 String targetId, String status, String fileName, String fileType) { }

    private AttachmentRow findAttachment(String attachmentId) {
        var list = jdbcTemplate.query(
                "SELECT attachment_id, upload_user_id, target_type, target_id, status, file_name, file_type "
                        + "FROM t_attachment WHERE attachment_id = ?",
                (rs, rowNum) -> new AttachmentRow(
                        rs.getString("attachment_id"),
                        rs.getString("upload_user_id"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("status"),
                        rs.getString("file_name"),
                        rs.getString("file_type")),
                attachmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 能不能看这个附件。判断逻辑见类注释「下载鉴权」。 */
    private boolean canAccess(String userId, String userType, AttachmentRow row) {
        if ("TEMP".equals(row.status())) {
            return userId.equals(row.uploadUserId());
        }
        if ("WORK_ORDER".equals(row.targetType())) {
            return countVisibleWorkOrder(row.targetId(), userId, userType) > 0;
        }
        if ("MESSAGE".equals(row.targetType())) {
            return countVisibleMessage(row.targetId(), userId, userType) > 0;
        }
        return false;
    }

    private int countVisibleWorkOrder(String workOrderId, String userId, String userType) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order "
                        + "WHERE work_order_id = ? AND (user_id = ? OR handler_id = ? OR ? = 'ADMIN')",
                Integer.class, workOrderId, userId, userId, userType);
        return c == null ? 0 : c;
    }

    private int countVisibleMessage(String messageId, String userId, String userType) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order_message m "
                        + "INNER JOIN t_work_order w ON w.work_order_id = m.work_order_id "
                        + "WHERE m.message_id = ? AND (w.user_id = ? OR w.handler_id = ? OR ? = 'ADMIN')",
                Integer.class, messageId, userId, userId, userType);
        return c == null ? 0 : c;
    }

    /**
     * 算出文件在磁盘上的绝对路径：{root}/{attachmentId 里的 yyyyMMdd}/{attachmentId}.{ext}。
     * 日期直接从编号里切（ATT + 8 位日期 + 6 位序号），所以不需要在库里另存路径。
     */
    private Path resolvePath(String attachmentId, String ext) {
        String datePart = attachmentId.substring(ID_PREFIX.length(), ID_PREFIX.length() + 8);
        String fileName = ext.isEmpty() ? attachmentId : attachmentId + "." + ext;
        return rootDir.resolve(datePart).resolve(fileName).normalize();
    }

    /** 只保留文件名最后一段，挡掉 "..\\..\\x" 这类路径穿越；顺便去掉首尾空格。 */
    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        return cleaned.trim();
    }

    /** 取小写后缀（不含点）；没有后缀返回空串。 */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Magic Number（文件头）校验，对应文档 §6 第 3 步「防止改扩展名伪装」。
     *
     * 说明两点局限，避免误解：
     *   · OOXML（docx/xlsx/pptx）和 zip 的文件头**本来就一样**（都是 PK\x03\x04），
     *     所以只能验到"它确实是个 zip 容器"，分不出具体是哪种 —— 这是格式本身决定的。
     *   · txt 没有固定文件头，跳过校验。
     */
    private static boolean magicMatches(String ext, byte[] content) {
        return switch (ext) {
            case "jpg", "jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
            case "png" -> startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "gif" -> startsWithAscii(content, 0, "GIF87a") || startsWithAscii(content, 0, "GIF89a");
            case "bmp" -> startsWith(content, 0x42, 0x4D);
            case "webp" -> startsWithAscii(content, 0, "RIFF") && startsWithAscii(content, 8, "WEBP");
            case "pdf" -> startsWithAscii(content, 0, "%PDF-");
            case "zip", "docx", "xlsx", "pptx" -> startsWith(content, 0x50, 0x4B, 0x03, 0x04);
            case "doc", "xls", "ppt" -> startsWith(content, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "rar" -> startsWith(content, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07);
            case "txt" -> true;   // 纯文本没有固定文件头
            default -> true;      // 白名单里其它类型暂不校验（新增后缀时记得补这里）
        };
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] content, int offset, String text) {
        if (content.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((content[offset + i] & 0xFF) != (text.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** 读图片长边像素。读不出来（格式不被 ImageIO 支持）就返回 0，表示"跳过这项校验"。 */
    private static int readMaxImageEdge(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return 0;
            }
            return Math.max(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删不掉就算了，最多留个孤儿文件，不该因此影响主流程
        }
    }
}
