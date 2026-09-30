<template>
  <div class="gf-page">
    <div class="gf-page-head">
      <div>
        <h2 class="gf-title">裁片套件封包交接</h2>
        <p class="gf-subtitle">
          同一订单同一裁剪批次的前片 / 后片 / 袖片 / 领片 / 里布逐片扫码，
          缺片、重复片、左右混放、跨面料批次、尺码冲突全部清零才能封包；封签交接、接收复核、异常留痕
        </p>
      </div>
      <el-button type="primary" @click="openCreate">＋ 建立套件</el-button>
    </div>

    <div class="gf-metric-row">
      <div class="gf-metric"><div class="label">草稿</div>
        <div class="value">{{ metrics.DRAFT || 0 }}</div></div>
      <div class="gf-metric"><div class="label">已封包</div>
        <div class="value" style="color: var(--gf-gold)">{{ metrics.SEALED || 0 }}</div></div>
      <div class="gf-metric"><div class="label">已交接待接收</div>
        <div class="value" style="color: var(--gf-primary)">{{ metrics.HANDED_OVER || 0 }}</div></div>
      <div class="gf-metric"><div class="label">异常处理中</div>
        <div class="value" style="color: var(--gf-danger)">{{ metrics.EXCEPTION || 0 }}</div></div>
      <div class="gf-metric"><div class="label">已接收闭环</div>
        <div class="value" style="color: var(--gf-ok)">{{ metrics.RECEIVED || 0 }}</div></div>
    </div>

    <div class="gf-toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="reload">
        <el-option v-for="(name, code) in meta.statuses || {}" :key="code" :label="name" :value="code" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="搜套件号 / 订单号 / 裁剪批次 / 服装" clearable
                style="width: 260px" @keyup.enter="reload" />
      <el-button type="primary" plain @click="reload">查询</el-button>
      <el-button @click="refreshAll" :loading="loading">刷新状态</el-button>
      <span class="spacer" />
    </div>

    <div class="gf-card" style="margin-top: 12px">
      <el-table :data="rows" border stripe size="small" v-loading="loading"
                @row-click="openKit" :row-class-name="rowClass" empty-text="暂无套件，点击右上角建立">
        <el-table-column prop="kitNo" label="套件号" width="170" />
        <el-table-column label="订单 / 服装" min-width="210">
          <template #default="{ row }">
            <div style="font-weight: 600">{{ row.orderNo }} · {{ row.garmentName }}</div>
            <div class="gf-muted">批次 {{ row.cutBatchNo }} · 尺码 {{ row.sizeMark }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)" effect="plain">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="裁片" width="100">
          <template #default="{ row }">
            {{ row.expectedTotal }} 片
          </template>
        </el-table-column>
        <el-table-column label="封签 / 交接" min-width="210">
          <template #default="{ row }">
            <div class="gf-muted" v-if="!row.sealNo">未封包</div>
            <template v-else>
              <div>封签 {{ row.sealNo }}</div>
              <div class="gf-muted" v-if="row.handedBy">
                {{ row.handedBy }} 交 → {{ row.receiverName }}
                <template v-if="row.receivedAt">；{{ row.receivedBy }} 已接收</template>
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="异常" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.exceptionCount" size="small" type="danger">{{ row.exceptionCount }}</el-tag>
            <span v-else class="gf-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="建档人" width="90" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openKit(row)">工作台</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ================= 建立套件 ================= -->
    <el-dialog v-model="createVisible" title="建立裁片套件" width="520px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="订单号" required>
          <el-input v-model="createForm.orderNo" placeholder="如 ORD-20260005" />
        </el-form-item>
        <el-form-item label="裁剪批次" required>
          <el-input v-model="createForm.cutBatchNo" placeholder="如 CUT-0929-A" />
        </el-form-item>
        <el-form-item label="服装名称" required>
          <el-input v-model="createForm.garmentName" placeholder="如 藏青精纺羊毛修身西装" />
        </el-form-item>
        <el-form-item label="尺码标记" required>
          <el-input v-model="createForm.sizeMark" placeholder="如 M / 48" />
        </el-form-item>
        <el-form-item label="裁剪人员" required>
          <el-input v-model="createForm.operator" placeholder="建档人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">建立并进入工作台</el-button>
      </template>
    </el-dialog>

    <!-- ================= 套件工作台 ================= -->
    <el-drawer v-model="kitVisible" size="76%" :destroy-on-close="true">
      <template #header>
        <div v-if="kit" style="margin-right: 40px">
          <div style="font-size: 16px; font-weight: 700">
            {{ kit.kitNo }}
            <el-tag size="small" :type="statusTag(kit.status)" effect="plain" style="margin-left: 8px">
              {{ kit.statusName }}
            </el-tag>
            <el-tag v-if="kit.exceptionNo" size="small" type="danger" effect="plain" style="margin-left: 6px">
              {{ kit.exceptionNo }}
            </el-tag>
          </div>
          <div class="gf-muted" style="margin-top: 2px">
            {{ kit.orderNo }} · {{ kit.garmentName }} · 裁剪批次 {{ kit.cutBatchNo }}
            · 尺码 {{ kit.sizeMark }} · 建档人 {{ kit.createdBy }}
          </div>
        </div>
      </template>

      <div v-if="kit" style="padding: 0 20px 24px">
        <el-alert v-if="kit.status === 'EXCEPTION'" type="error" :closable="false" show-icon
                  style="margin-bottom: 12px"
                  title="套件处于异常状态：已交接的套件不能回草稿，请在「异常处理」区逐片补扫并复核" />
        <el-alert v-if="kit.sealNo && kit.status !== 'RECEIVED'" type="warning" :closable="false" show-icon
                  style="margin-bottom: 12px"
                  :title="`封签 ${kit.sealNo}；封包后清单冻结，确需改动请在仍为已封包时拆包并填写原因`" />

        <el-tabs v-model="activeTab">
          <!-- ===== 清单 ===== -->
          <el-tab-pane label="① 应有清单" name="manifest">
            <div class="ck-bar">
              <el-button type="primary" plain size="small" @click="addManifestRow"
                         :disabled="!isDraft">＋ 加一行</el-button>
              <el-button size="small" @click="loadManifestFromKit" :disabled="!isDraft">重置为已存清单</el-button>
              <el-button type="primary" size="small" :loading="saving" @click="saveManifest"
                         :disabled="!isDraft">保存清单</el-button>
              <span class="gf-muted" style="margin-left: 8px">
                清单合计 <b>{{ manifestTotal }}</b> 片；封包后此页只读
              </span>
            </div>
            <el-table :data="manifestRows" border size="small">
              <el-table-column type="index" label="#" width="44" />
              <el-table-column label="部位" width="150">
                <template #default="{ row }">
                  <el-select v-model="row.part" :disabled="!isDraft" size="small">
                    <el-option v-for="(name, code) in meta.parts || {}" :key="code" :label="name" :value="code" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="左右侧" width="140">
                <template #default="{ row }">
                  <el-select v-model="row.side" :disabled="!isDraft" size="small">
                    <el-option v-for="(name, code) in meta.sides || {}" :key="code" :label="name" :value="code" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="面料批次" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.fabricBatchNo" :disabled="!isDraft" size="small" placeholder="如 FB3001-A" />
                </template>
              </el-table-column>
              <el-table-column label="尺码标记" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.sizeMark" :disabled="!isDraft" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="数量" width="90">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="1" :max="999" size="small"
                                   :disabled="!isDraft" controls-position="right" style="width: 78px" />
                </template>
              </el-table-column>
              <el-table-column width="60">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" :disabled="!isDraft"
                             @click="manifestRows.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ===== 逐片扫码 ===== -->
          <el-tab-pane :label="`② 逐片扫码（${kit.packingScans?.length || 0}）`" name="scan">
            <div class="ck-bar">
              <el-input v-model="scanCode" placeholder="扫码 / 输入裁片条码后回车" style="width: 260px"
                        clearable @keyup.enter="quickScan" :disabled="!isDraft" />
              <el-button type="primary" size="small" @click="quickScan" :disabled="!isDraft">扫码入套</el-button>
              <el-button size="small" @click="bulkDialog = true" :disabled="!isDraft">批量导入 200 片…</el-button>
              <span class="gf-muted" style="margin-left: 8px" v-if="!isDraft">封包后实际片只读</span>
            </div>
            <el-form inline v-if="isDraft" style="margin: 6px 0">
              <el-form-item label="部位">
                <el-select v-model="scanForm.part" style="width: 120px">
                  <el-option v-for="(name, code) in meta.parts || {}" :key="code" :label="name" :value="code" />
                </el-select>
              </el-form-item>
              <el-form-item label="左右">
                <el-select v-model="scanForm.side" style="width: 104px">
                  <el-option v-for="(name, code) in meta.sides || {}" :key="code" :label="name" :value="code" />
                </el-select>
              </el-form-item>
              <el-form-item label="批次">
                <el-input v-model="scanForm.fabricBatchNo" style="width: 120px" />
              </el-form-item>
              <el-form-item label="尺码">
                <el-input v-model="scanForm.sizeMark" style="width: 80px" />
              </el-form-item>
            </el-form>
            <el-table :data="kit.packingScans || []" border size="small" max-height="380">
              <el-table-column prop="seq" label="#" width="48" />
              <el-table-column prop="pieceCode" label="裁片条码" width="180" />
              <el-table-column label="部位" width="110">
                <template #default="{ row }">{{ row.partName }}</template>
              </el-table-column>
              <el-table-column label="左右" width="90">
                <template #default="{ row }">{{ row.sideName }}</template>
              </el-table-column>
              <el-table-column prop="fabricBatchNo" label="面料批次" width="130" />
              <el-table-column prop="sizeMark" label="尺码" width="80" />
              <el-table-column prop="scannedBy" label="扫码人" width="90" />
              <el-table-column label="操作" width="70">
                <template #default="{ row }">
                  <el-button link type="danger" size="small" :disabled="!isDraft"
                             @click="removeScan(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ===== 封包校验 ===== -->
          <el-tab-pane label="③ 封包校验 / 封包" name="seal">
            <div class="ck-bar">
              <el-button size="small" @click="runValidate" :loading="saving">重新校验</el-button>
              <el-button type="primary" size="small" :loading="saving"
                         :disabled="!isDraft || !(validation?.passed)" @click="seal">
                校验通过，执行封包
              </el-button>
              <el-button size="small" type="warning" :disabled="kit.status !== 'SEALED'" @click="unsealDialog = true">
                拆包（已封包未交接）
              </el-button>
              <span class="gf-muted" style="margin-left:8px">
                应有 {{ validation?.expectedTotal ?? '-' }} 片，实扫 {{ validation?.actualTotal ?? '-' }} 片
              </span>
            </div>

            <el-result v-if="validation?.passed" icon="success" title="实际裁片与应有清单完全一致"
                       sub-title="可以封包；封包后生成封签、清单冻结" />
            <el-result v-else icon="error" title="存在差异，封包将被服务端拦截"
                       :sub-title="validation?.message || '点击重新校验获取结果'" />

            <el-table :data="validation?.diffs || []" border size="small" max-height="300"
                      empty-text="无差异">
              <el-table-column label="类型" width="110">
                <template #default="{ row }">
                  <el-tag size="small" type="danger" effect="plain">{{ diffName(row.type) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="pieceCode" label="条码" width="160" />
              <el-table-column label="部位/侧" width="150">
                <template #default="{ row }">
                  {{ partName(row.part) }}{{ sideName(row.side) }}
                </template>
              </el-table-column>
              <el-table-column prop="fabricBatchNo" label="面料批次" width="120" />
              <el-table-column prop="sizeMark" label="尺码" width="80" />
              <el-table-column label="应/实" width="80">
                <template #default="{ row }">{{ row.expectedQty }} / {{ row.actualQty }}</template>
              </el-table-column>
              <el-table-column prop="message" label="说明" min-width="220" />
            </el-table>
          </el-tab-pane>

          <!-- ===== 交接 / 接收 ===== -->
          <el-tab-pane label="④ 交接 / 接收" name="handover">
            <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
              <el-descriptions-item label="封签编号">
                {{ kit.sealNo || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="裁片总数">{{ kit.expectedTotal }}</el-descriptions-item>
              <el-descriptions-item label="交出人">{{ kit.handedBy || '—' }}</el-descriptions-item>
              <el-descriptions-item label="交接时间">{{ fmt(kit.handedOverAt) || '—' }}</el-descriptions-item>
              <el-descriptions-item label="指定接收人">{{ kit.receiverName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="实际接收人">{{ kit.receivedBy || '—' }}
                {{ fmt(kit.receivedAt) }}</el-descriptions-item>
            </el-descriptions>

            <template v-if="kit.status === 'SEALED'">
              <el-form inline>
                <el-form-item label="交出人">
                  <el-input v-model="handForm.operator" style="width: 120px" placeholder="交出人" />
                </el-form-item>
                <el-form-item label="接收人">
                  <el-input v-model="handForm.receiverName" style="width: 120px" placeholder="指定接收人" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="saving" @click="handOver">
                    确认交接（重复点击幂等）
                  </el-button>
                </el-form-item>
              </el-form>
            </template>

            <template v-if="kit.status === 'HANDED_OVER'">
              <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                        title="接收方必须再次核对封签与裁片数量。可只核封签+总数，也可逐片重扫全量比对。" />
              <el-form label-width="92px" style="max-width: 560px">
                <el-form-item label="接收操作人">
                  <el-input v-model="receiveForm.operator" placeholder="接收人" />
                </el-form-item>
                <el-form-item label="核对封签">
                  <el-input v-model="receiveForm.sealNo" placeholder="扫描套件封签编号" />
                </el-form-item>
                <el-form-item label="逐片清点">
                  <el-button size="small" type="primary" plain @click="receivingScanDialog = true">
                    逐片重扫裁片（必做）
                  </el-button>
                  <span class="gf-muted" style="margin-left: 8px">
                    已录 {{ receiveForm.pieces?.length || 0 }} 片，须与清单 {{ kit.expectedTotal }} 片完全一致
                  </span>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="saving" @click="receive">核对一致，确认接收</el-button>
                  <el-button type="danger" plain @click="manualExceptionDialog = true">
                    发现漏片/混片，登记异常
                  </el-button>
                </el-form-item>
              </el-form>
            </template>

            <el-result v-if="kit.status === 'RECEIVED'" icon="success"
                       :title="`已由 ${kit.receivedBy} 接收，交接闭环完成`"
                       :sub-title="`接收时间 ${fmt(kit.receivedAt)}`" />
            <el-alert v-if="kit.status === 'EXCEPTION'" type="error" :closable="false"
                      title="套件异常处理中，请到「异常处理」页补齐复核" />
          </el-tab-pane>

          <!-- ===== 异常处理 ===== -->
          <el-tab-pane label="⑤ 异常处理" name="exception">
            <template v-if="kit.exceptions?.length">
              <el-table :data="kit.exceptions" border size="small" style="margin-bottom: 12px">
                <el-table-column prop="exceptionNo" label="异常单号" width="180" />
                <el-table-column prop="reason" label="情况" min-width="200" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'success'">
                      {{ row.status === 'OPEN' ? '处理中' : '已复核关闭' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="raisedBy" label="登记人" width="90" />
                <el-table-column label="登记时间" width="160">
                  <template #default="{ row }">{{ fmt(row.raisedAt) }}</template>
                </el-table-column>
                <el-table-column label="复核" min-width="170">
                  <template #default="{ row }">
                            <span v-if="row.status === 'RESOLVED'">
                              {{ row.resolvedBy }} · {{ fmt(row.resolvedAt) }}<br />
                              <span class="gf-muted">{{ row.resolveNote }}</span>
                            </span>
                    <span v-else class="gf-muted">待补齐复核</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
            <el-empty v-else description="暂无异常记录" :image-size="70" />

            <template v-if="kit.status === 'EXCEPTION'">
              <div class="ck-bar">
                <el-button size="small" @click="resolveScanDialog = true">逐片补扫（整轮重扫）</el-button>
                <span class="gf-muted">已补扫 {{ kit.resolveScans?.length || 0 }} 片</span>
              </div>
              <el-form label-width="92px" style="max-width: 620px; margin-top: 8px">
                <el-form-item label="复核说明">
                  <el-input v-model="resolveForm.reason" type="textarea" :rows="2"
                            placeholder="补齐处理说明，复核通过必填" />
                </el-form-item>
                <el-form-item label="复核人">
                  <el-input v-model="resolveForm.operator" style="width: 160px" placeholder="复核人" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="saving" @click="resolveReview">
                    补齐并复核（与清单完全一致才通过）
                  </el-button>
                </el-form-item>
              </el-form>
              <el-table v-if="kit.resolveScans?.length" :data="kit.resolveScans" border size="small" max-height="240">
                <el-table-column prop="pieceCode" label="条码" width="180" />
                <el-table-column label="部位" width="120">
                  <template #default="{ row }">{{ row.partName }}{{ row.sideName }}</template>
                </el-table-column>
                <el-table-column prop="fabricBatchNo" label="批次" width="130" />
                <el-table-column prop="sizeMark" label="尺码" width="80" />
              </el-table>
            </template>
          </el-tab-pane>

          <!-- ===== 时间线 ===== -->
          <el-tab-pane label="⑥ 历史时间线" name="timeline">
            <el-timeline>
              <el-timeline-item v-for="ev in timeline" :key="ev.id" :timestamp="fmt(ev.occurredAt)"
                                placement="top" :type="eventTypeColor(ev.eventType)">
                <el-card shadow="never">
                  <div style="font-weight: 600">
                    {{ ev.eventName }}
                    <span class="gf-muted" style="font-weight: 400; margin-left: 8px">{{ ev.operator }}</span>
                    <el-tag v-if="ev.toStatus && ev.fromStatus !== ev.toStatus" size="small" effect="plain"
                            style="margin-left: 8px">
                      {{ statusName(ev.fromStatus) }} → {{ statusName(ev.toStatus) }}
                    </el-tag>
                  </div>
                  <div v-if="ev.detail" class="ck-json">
                            <pre>{{ JSON.stringify(ev.detail, null, 2) }}</pre>
                  </div>
                  <el-collapse v-if="ev.before || ev.after" style="margin-top: 4px">
                    <el-collapse-item title="查看拆包 / 状态变化前后完整快照">
                      <el-row :gutter="8">
                        <el-col :span="12" v-if="ev.before">
                          <div class="gf-muted">操作前</div>
                          <pre class="ck-json-pre">{{ JSON.stringify(ev.before, null, 2) }}</pre>
                        </el-col>
                        <el-col :span="12" v-if="ev.after">
                          <div class="gf-muted">操作后</div>
                          <pre class="ck-json-pre">{{ JSON.stringify(ev.after, null, 2) }}</pre>
                        </el-col>
                      </el-row>
                    </el-collapse-item>
                  </el-collapse>
                </el-card>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <!-- 拆包原因 -->
    <el-dialog v-model="unsealDialog" title="拆包登记" width="460px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
                title="拆包后回到草稿，封签作废；拆包前后完整清单与时间将保留在时间线中" />
      <el-form label-width="72px">
        <el-form-item label="操作人"><el-input v-model="unsealForm.operator" /></el-form-item>
        <el-form-item label="拆包原因" required>
          <el-input v-model="unsealForm.reason" type="textarea" :rows="3" placeholder="必须填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unsealDialog = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="unseal">确认拆包</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入 -->
    <el-dialog v-model="bulkDialog" title="批量生成 / 粘贴裁片条码" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
                title="按清单生成 200 片连续条码（验收性能用）；也可粘贴 JSON 数组" />
      <el-form label-width="110px">
        <el-form-item label="条码前缀">
          <el-input v-model="bulkForm.prefix" style="width: 200px" placeholder="如 P" />
        </el-form-item>
        <el-form-item label="JSON 粘贴">
          <el-input v-model="bulkForm.json" type="textarea" :rows="6"
                    placeholder='[{"pieceCode":"P001","part":"FRONT","side":"LEFT","fabricBatchNo":"B1","sizeMark":"M"}]' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bulkDialog = false">取消</el-button>
        <el-button @click="bulkGenerate">按清单生成</el-button>
        <el-button type="primary" :loading="saving" @click="bulkSubmit">提交扫码</el-button>
      </template>
    </el-dialog>

    <!-- 接收逐片重扫 -->
    <el-dialog v-model="receivingScanDialog" title="接收方逐片重扫" width="560px">
      <el-form label-width="110px">
        <el-form-item label="按封包清单生成">
          <el-button size="small" @click="receivePiecesFromManifest">生成正确重扫（模拟全数核对）</el-button>
        </el-form-item>
        <el-form-item label="重扫 JSON">
          <el-input v-model="receiveJson" type="textarea" :rows="10"
                    placeholder='[{"pieceCode":"P001","part":"FRONT","side":"LEFT","fabricBatchNo":"B1","sizeMark":"M"}]' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receivingScanDialog = false">取消</el-button>
        <el-button type="primary" @click="parseReceivePieces">解析保存</el-button>
      </template>
    </el-dialog>

    <!-- 异常补扫 -->
    <el-dialog v-model="resolveScanDialog" title="异常补齐逐片重扫" width="560px">
      <el-form label-width="110px">
        <el-form-item>
          <el-button size="small" @click="resolveGenerate">按清单生成完整重扫</el-button>
          <span class="gf-muted" style="margin-left:8px">也可在下方粘贴 JSON 调整缺/混情形</span>
        </el-form-item>
        <el-form-item label="补扫人">
          <el-input v-model="resolveForm.operator" style="width: 160px" />
        </el-form-item>
        <el-form-item label="重扫 JSON">
          <el-input v-model="resolveJson" type="textarea" :rows="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveScanDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitResolveScans">提交补扫</el-button>
      </template>
    </el-dialog>

    <!-- 手工登记异常 -->
    <el-dialog v-model="manualExceptionDialog" title="登记接收异常" width="480px">
      <el-form label-width="84px">
        <el-form-item label="登记人"><el-input v-model="receiveForm.operator" /></el-form-item>
        <el-form-item label="情况说明" required>
          <el-input v-model="manualReason" type="textarea" :rows="3"
                    placeholder="如：清点发现左袖片缺失、右袖片混入两包" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualExceptionDialog = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="raiseManual">登记异常</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cutKitApi } from '../api/index.js'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const metrics = ref({})
const meta = ref({})
const query = reactive({ status: '', keyword: '', page: 0, size: 50 })

const kitVisible = ref(false)
const kit = ref(null)
const timeline = ref([])
const activeTab = ref('manifest')
const validation = ref(null)

const createVisible = ref(false)
const createForm = reactive({ orderNo: '', cutBatchNo: '', garmentName: '', sizeMark: 'M', operator: '' })

const manifestRows = ref([])
const scanCode = ref('')
const scanForm = reactive({ part: 'FRONT', side: 'LEFT', fabricBatchNo: '', sizeMark: 'M' })

const handForm = reactive({ operator: '', receiverName: '', requestId: '' })
const receiveForm = reactive({ operator: '', sealNo: '', pieces: [] })
const receiveJson = ref('')
const manualExceptionDialog = ref(false)
const manualReason = ref('')

const unsealDialog = ref(false)
const unsealForm = reactive({ operator: '', reason: '' })

const resolveForm = reactive({ operator: '', reason: '' })
const resolveScanDialog = ref(false)
const resolveJson = ref('')

const bulkDialog = ref(false)
const bulkForm = reactive({ prefix: 'P', json: '' })
const receivingScanDialog = ref(false)

const isDraft = computed(() => kit.value?.status === 'DRAFT')
const manifestTotal = computed(() =>
  manifestRows.value.reduce((sum, r) => sum + (Number(r.quantity) || 0), 0))

onMounted(async () => {
  try {
    meta.value = await cutKitApi.meta()
  } catch (e) { /* 拦截器已提示 */ }
  await reload()
})

async function reload() {
  loading.value = true
  try {
    const data = await cutKitApi.list({ ...query })
    rows.value = data.content || []
    metrics.value = {}
    // 不分状态查全量一次用于指标（数据量小）
    const all = await cutKitApi.list({ page: 0, size: 200 })
    for (const k of all.content || []) {
      metrics.value[k.status] = (metrics.value[k.status] || 0) + 1
    }
  } finally {
    loading.value = false
  }
}

async function refreshAll() {
  await reload()
  if (kit.value) {
    await loadKit(kit.value.id, false)
  }
}

function rowClass({ row }) {
  return row.status === 'EXCEPTION' ? 'gf-row-danger' : ''
}

function statusTag(s) {
  return { DRAFT: 'info', SEALED: 'warning', HANDED_OVER: 'primary', RECEIVED: 'success', EXCEPTION: 'danger' }[s] || ''
}
function statusName(s) { return meta.value.statuses?.[s] || s || '' }
function partName(p) { return meta.value.parts?.[p] || p || '' }
function sideName(s) { return s === 'LEFT' ? '（左）' : s === 'RIGHT' ? '（右）' : '' }
function diffName(t) { return meta.value.diffTypes?.[t] || t }
function fmt(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}
function eventTypeColor(t) {
  return {
    SEAL: 'warning', HAND_OVER: 'primary', RECEIVE: 'success',
    RECEIVE_FAIL: 'danger', RAISE_EXCEPTION: 'danger', UNSEAL: 'warning',
    RESOLVE_EXCEPTION: 'success'
  }[t] || ''
}

function openCreate() {
  Object.assign(createForm, { orderNo: '', cutBatchNo: '', garmentName: '', sizeMark: 'M', operator: '' })
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.orderNo || !createForm.cutBatchNo || !createForm.garmentName || !createForm.operator) {
    ElMessage.warning('请填完订单号、裁剪批次、服装名称和建档人')
    return
  }
  saving.value = true
  try {
    const created = await cutKitApi.create({ ...createForm })
    createVisible.value = false
    await reload()
    await openKit(created)
  } finally {
    saving.value = false
  }
}

async function openKit(row) {
  await loadKit(row.id, true)
  kitVisible.value = true
}

async function loadKit(id, resetTab) {
  const data = await cutKitApi.detail(id)
  kit.value = data
  loadManifestFromKit()
  Object.assign(scanForm, {
    part: data.manifest?.[0]?.part || 'FRONT',
    side: 'LEFT',
    fabricBatchNo: data.manifest?.[0]?.fabricBatchNo || '',
    sizeMark: data.sizeMark || 'M'
  })
  validation.value = data.latestValidation || null
  timeline.value = await cutKitApi.timeline(id)
  if (resetTab) activeTab.value = 'manifest'
  if (data.status === 'EXCEPTION') activeTab.value = 'exception'
  if (data.status === 'HANDED_OVER') {
    receiveForm.sealNo = ''
    handForm.operator = data.handedBy || ''
  }
}

function loadManifestFromKit() {
  if (!kit.value) return
  manifestRows.value = (kit.value.manifest || []).map((r) => ({
    part: r.part, side: r.side, fabricBatchNo: r.fabricBatchNo, sizeMark: r.sizeMark, quantity: r.quantity
  }))
}

function addManifestRow() {
  manifestRows.value.push({ part: 'FRONT', side: 'CENTER', fabricBatchNo: '', sizeMark: kit.value.sizeMark, quantity: 1 })
}

async function saveManifest() {
  if (manifestRows.value.some((r) => !r.fabricBatchNo)) {
    ElMessage.warning('每一行都要填面料批次')
    return
  }
  saving.value = true
  try {
    await cutKitApi.saveManifest(kit.value.id, { items: manifestRows.value, operator: kit.value.createdBy })
    ElMessage.success('清单已保存')
    await loadKit(kit.value.id, false)
  } finally {
    saving.value = false
  }
}

async function quickScan() {
  if (!scanCode.value) return
  saving.value = true
  try {
    await cutKitApi.scan(kit.value.id, {
      operator: kit.value.createdBy,
      pieces: [{
        pieceCode: scanCode.value.trim(),
        part: scanForm.part, side: scanForm.side,
        fabricBatchNo: scanForm.fabricBatchNo, sizeMark: scanForm.sizeMark
      }]
    })
    scanCode.value = ''
    await loadKit(kit.value.id, false)
  } finally {
    saving.value = false
  }
}

async function removeScan(row) {
  await ElMessageBox.confirm(`移除扫码 ${row.pieceCode}？动作仍会写入时间线`, '确认', { type: 'warning' })
  saving.value = true
  try {
    await cutKitApi.removeScan(kit.value.id, row.id, kit.value.createdBy)
    await loadKit(kit.value.id, false)
  } finally {
    saving.value = false
  }
}

async function runValidate() {
  saving.value = true
  try {
    validation.value = await cutKitApi.validate(kit.value.id)
  } finally {
    saving.value = false
  }
}

async function seal() {
  saving.value = true
  try {
    await cutKitApi.seal(kit.value.id, { operator: kit.value.createdBy })
    ElMessage.success('封包成功')
    await loadKit(kit.value.id, false)
    activeTab.value = 'handover'
  } finally {
    saving.value = false
  }
}

async function unseal() {
  if (!unsealForm.reason) {
    ElMessage.warning('拆包必须填写原因')
    return
  }
  saving.value = true
  try {
    await cutKitApi.unseal(kit.value.id, { ...unsealForm })
    unsealDialog.value = false
    unsealForm.reason = ''
    await loadKit(kit.value.id, false)
    activeTab.value = 'manifest'
  } finally {
    saving.value = false
  }
}

async function handOver() {
  if (!handForm.operator || !handForm.receiverName) {
    ElMessage.warning('交出人和接收人都要填写')
    return
  }
  saving.value = true
  try {
    await cutKitApi.handOver(kit.value.id, {
      operator: handForm.operator,
      receiverName: handForm.receiverName,
      requestId: reqId('HO')
    })
    ElMessage.success('交接成功')
    await loadKit(kit.value.id, false)
    activeTab.value = 'handover'
  } finally {
    saving.value = false
  }
}

async function receive() {
  if (!receiveForm.operator || !receiveForm.sealNo) {
    ElMessage.warning('接收操作人和封签编号必填')
    return
  }
  if (!receiveForm.pieces?.length) {
    ElMessage.warning('接收方必须再次逐片清点裁片，请先录入重扫裁片')
    return
  }
  saving.value = true
  try {
    await cutKitApi.receive(kit.value.id, {
      ...receiveForm,
      requestId: reqId('RC')
    })
    ElMessage.success('接收成功，闭环完成')
    await loadKit(kit.value.id, false)
    activeTab.value = 'handover'
  } finally {
    saving.value = false
  }
}

async function raiseManual() {
  if (!manualReason.value) {
    ElMessage.warning('必须填写异常情况')
    return
  }
  saving.value = true
  try {
    await cutKitApi.raiseException(kit.value.id, {
      operator: receiveForm.operator || '接收方',
      reason: manualReason.value
    })
    manualExceptionDialog.value = false
    manualReason.value = ''
    await loadKit(kit.value.id, false)
    activeTab.value = 'exception'
  } finally {
    saving.value = false
  }
}

function receivePiecesFromManifest() {
  receiveJson.value = JSON.stringify((kit.value.manifest || []).flatMap(expandManifestRow), null, 2)
}

function expandManifestRow(row, idx) {
  // 按清单顺序产出连续条码（与封包扫码条码规则保持一致前缀可能不同，接收重扫是独立凭证）
  const out = []
  const base = idx * 10
  for (let i = 0; i < row.quantity; i++) {
    out.push({
      pieceCode: `R${String(base + i + 1).padStart(4, '0')}`,
      part: row.part, side: row.side,
      fabricBatchNo: row.fabricBatchNo, sizeMark: row.sizeMark
    })
  }
  return out
}

function parseReceivePieces() {
  try {
    const arr = JSON.parse(receiveJson.value || '[]')
    if (!Array.isArray(arr)) throw new Error()
    receiveForm.pieces = arr
    ElMessage.success(`已保存 ${arr.length} 片重扫记录`)
    receivingScanDialog.value = false
  } catch (e) {
    ElMessage.error('JSON 格式不正确')
  }
}

function resolveGenerate() {
  resolveJson.value = JSON.stringify((kit.value.manifest || []).flatMap(expandManifestRow), null, 2)
}

async function submitResolveScans() {
  let arr
  try {
    arr = JSON.parse(resolveJson.value || '[]')
    if (!Array.isArray(arr) || !arr.length) throw new Error()
  } catch (e) {
    ElMessage.error('JSON 格式不正确或为空')
    return
  }
  saving.value = true
  try {
    await cutKitApi.resolveScans(kit.value.id, {
      operator: resolveForm.operator || '异常处理人', pieces: arr
    })
    resolveScanDialog.value = false
    await loadKit(kit.value.id, false)
  } finally {
    saving.value = false
  }
}

async function resolveReview() {
  if (!resolveForm.operator || !resolveForm.reason) {
    ElMessage.warning('复核人和处理说明必填')
    return
  }
  saving.value = true
  try {
    await cutKitApi.resolveReview(kit.value.id, { ...resolveForm })
    ElMessage.success('复核通过，套件回到已交接')
    await loadKit(kit.value.id, false)
    activeTab.value = 'handover'
  } finally {
    saving.value = false
  }
}

// ---- 批量 200 片生成（验收：正常封包 + 性能） ----
function bulkGenerate() {
  const pieces = []
  let seq = 1
  for (const row of kit.value.manifest || []) {
    for (let i = 0; i < row.quantity; i++) {
      pieces.push({
        pieceCode: `${bulkForm.prefix}${String(seq).padStart(4, '0')}`,
        part: row.part, side: row.side,
        fabricBatchNo: row.fabricBatchNo, sizeMark: row.sizeMark
      })
      seq++
    }
  }
  bulkForm.json = JSON.stringify(pieces)
}

async function bulkSubmit() {
  let arr
  try {
    arr = JSON.parse(bulkForm.json || '[]')
    if (!Array.isArray(arr) || !arr.length) throw new Error()
  } catch (e) {
    ElMessage.error('JSON 格式不正确')
    return
  }
  saving.value = true
  try {
    await cutKitApi.scan(kit.value.id, { operator: kit.value.createdBy, pieces: arr })
    bulkDialog.value = false
    bulkForm.json = ''
    ElMessage.success(`已扫码 ${arr.length} 片`)
    await loadKit(kit.value.id, false)
    activeTab.value = 'seal'
    await runValidate()
  } finally {
    saving.value = false
  }
}

function reqId(prefix) {
  return `${prefix}-${kit.value.id}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}
</script>

<style scoped>
.ck-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.ck-json pre,
.ck-json-pre {
  margin: 4px 0 0;
  max-height: 260px;
  overflow: auto;
  background: var(--gf-bg);
  border: 1px solid var(--gf-line);
  border-radius: 8px;
  padding: 8px;
  font-size: 11px;
  line-height: 1.5;
}
:deep(.gf-row-danger) {
  background: #fdf0ef !important;
}
.spacer {
  flex: 1;
}
</style>
