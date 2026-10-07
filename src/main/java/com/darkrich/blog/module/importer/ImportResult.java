package com.darkrich.blog.module.importer;

import java.util.List;

/**
 * 批量导入的汇总结果。单个文件失败不影响其它文件，所以逐个文件返回状态，
 * 前端据此展示“成功 N / 跳过 N / 失败 N”和每个失败文件的原因。
 */
public record ImportResult(int total, int imported, int skipped, int failed, List<Item> items) {

    public enum Status {
        /** 已创建为草稿 */
        IMPORTED,
        /** 已存在同标题文章，未重复导入 */
        SKIPPED,
        /** 解析或保存失败，原因见 message */
        FAILED
    }

    /**
     * @param fileName  上传时的文件名（已去掉路径）
     * @param title     解析出的标题；解析失败时为 null
     * @param articleId 创建成功时的文章 id，否则为 null
     */
    public record Item(String fileName, Status status, String title, Long articleId, String message) {

        static Item imported(String fileName, String title, Long articleId) {
            return new Item(fileName, Status.IMPORTED, title, articleId, "已导入为草稿");
        }

        static Item skipped(String fileName, String title, String message) {
            return new Item(fileName, Status.SKIPPED, title, null, message);
        }

        static Item failed(String fileName, String message) {
            return new Item(fileName, Status.FAILED, null, null, message);
        }
    }

    public static ImportResult of(List<Item> items) {
        return new ImportResult(items.size(),
                count(items, Status.IMPORTED), count(items, Status.SKIPPED), count(items, Status.FAILED), items);
    }

    private static int count(List<Item> items, Status status) {
        return (int) items.stream().filter(i -> i.status() == status).count();
    }
}
