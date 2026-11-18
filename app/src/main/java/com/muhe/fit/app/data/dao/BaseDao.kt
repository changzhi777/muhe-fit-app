package com.muhe.fit.app.data.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

// 泛型 Dao 基座：八类条目的写入三件套（每表语义一致，Room 官方 BaseDao pattern）。
// 读侧（getAll/getSince/getById）与删除因表名/主键不同，由各 Dao 自行声明。
// 写入口径（对齐同步协议客户端半——上传是整档合并，客户端半负责本地增改）：
//   upsertAll / upsert  REPLACE 整行覆盖 = 条目级整体语义（拉取落库、本地增改唯一入口）；
//   update              按主键部分更新——透传场景勿用（易与整行覆盖语义打架），仅字段级 patch 时慎选。
abstract class BaseDao<T> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(items: List<T>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: T)

    @Update
    abstract suspend fun update(item: T)
}
