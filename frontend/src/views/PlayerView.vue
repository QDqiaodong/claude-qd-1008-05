<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const summaries = ref({})
const error = ref('')
const tip = ref('')
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editing = ref(false) // false=建档；true=编辑既有球员（带版本乐观锁）
const formError = ref('') // 弹窗内错误（空值拒绝 / 版本冲突），不与弹窗外的页面提示混用
const formRef = ref(null)
const rules = {
  playerNo: [{ required: true, message: '请输入球员编号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入球员姓名', trigger: 'blur' }]
}

const form = ref(blank())
function blank() {
  return { id: null, version: null, playerNo: '', name: '', handicap: 10, phone: '', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const [players, hcp] = await Promise.all([api.listPlayers(), api.handicapSummary()])
    rows.value = players || []
    const map = {}
    for (const s of (hcp || [])) map[s.playerId] = s
    summaries.value = map
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() {
  editing.value = false
  form.value = blank()
  formError.value = ''
  dialog.value = true; tip.value = ''; error.value = ''
}

/**
 * 编辑球员：以当前行（列表已加载的最新版本）填充表单。保存时把 version 作为
 * expectedVersion 带给服务端；他人在此期间改过资料时会收到 409，必须重新读取。
 */
function openEdit(row) {
  editing.value = true
  form.value = {
    id: row.id, version: row.version,
    playerNo: row.playerNo, name: row.name, handicap: row.handicap,
    phone: row.phone || '', note: row.note || ''
  }
  formError.value = ''
  dialog.value = true; tip.value = ''; error.value = ''
  formRef.value?.clearValidate?.()
}

/**
 * 冲突（409）或校验失败后：用服务端当前最新球员重建表单（版本号同步推进），
 * 管理员核对后可直接在最新资料上重试，不能拿着过期表单再覆盖。
 */
function resetFormToLatest(player) {
  if (!player || form.value.id !== player.id) return
  form.value = {
    id: player.id, version: player.version,
    playerNo: player.playerNo, name: player.name, handicap: player.handicap,
    phone: player.phone || '', note: player.note || ''
  }
}

async function save() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return // 必填项未过，停在弹窗里让管理员补
  }
  saving.value = true; formError.value = ''
  try {
    const d = { ...form.value, handicap: Number(form.value.handicap) }
    if (editing.value) {
      const latest = await api.updatePlayer(d.id, d, d.version)
      tip.value = `球员 ${latest.name} 的资料已更新（v${latest.version}）`
      dialog.value = false
      // 合法修改后重新读取：列表、差点详情、赛事/开球等页面都从服务端取数，一起显示新资料
      await load()
      if (hcp.value && hcp.value.playerId === d.id) {
        await loadHandicap(d.id)
      }
    } else {
      await api.createPlayer(d)
      tip.value = `球员 ${d.name} 已建档`
      dialog.value = false
      await load()
    }
  } catch (e) {
    if (e.isConflict) {
      // 过期操作：明确提示「数据已变化」，并重新读取当前最新球员重建弹窗，
      // 要求核对后重试；乐观锁兜底的 409 可能不带 data，则主动发一次查询
      let latest = e.data
      if ((!latest || latest.id !== form.value.id) && editing.value) {
        try { latest = await api.getPlayer(form.value.id) } catch { /* 读取失败保留提示 */ }
      }
      formError.value = `${e.message}（弹窗已按当前最新资料重新载入，请核对后重试）`
      resetFormToLatest(latest)
      await load()
    } else {
      // 空姓名/编号等服务端拒绝：弹窗保留，原资料未被改动，管理员可核对错误后修改
      formError.value = e.message
    }
  } finally { saving.value = false }
}

function statusType(h) {
  if (h <= 5) return 'success'
  if (h <= 18) return 'warning'
  return 'info'
}

// ---------------- 差点详情 ----------------
const hcpDialog = ref(false)
const hcpLoading = ref(false)
const recalcing = ref(false)
const hcp = ref(null)
const hcpHistory = ref([])

async function openHandicap(row) {
  hcpDialog.value = true
  await loadHandicap(row.id)
}
async function loadHandicap(playerId) {
  hcpLoading.value = true; error.value = ''
  try {
    const [view, history] = await Promise.all([
      api.playerHandicap(playerId), api.playerHandicapHistory(playerId)
    ])
    hcp.value = view
    hcpHistory.value = history || []
  } catch (e) { error.value = e.message } finally { hcpLoading.value = false }
}
async function recalcThis() {
  if (!hcp.value) return
  recalcing.value = true; error.value = ''; tip.value = ''
  try {
    const results = await api.recalcHandicaps({ playerIds: [hcp.value.playerId], asOfDate: null })
    const r = (results || [])[0]
    tip.value = r && r.ok
      ? `已重算：${hcp.value.playerName} 当前指数 ${r.indexValue == null ? '样本不足' : Number(r.indexValue).toFixed(1)}`
      : `重算失败：${r ? r.message : '未知错误'}`
    await loadHandicap(hcp.value.playerId)
    await load()
  } catch (e) { error.value = e.message } finally { recalcing.value = false }
}

const fmt = (n) => (n === null || n === undefined) ? '—' : Number(n).toFixed(1)
function changeText(c) {
  if (c === null || c === undefined) return '—'
  const v = Number(c)
  return (v > 0 ? '+' : '') + v.toFixed(1)
}
function changeClass(c) {
  if (c === null || c === undefined || Number(c) === 0) return 'muted'
  return Number(c) > 0 ? 'delta-up' : 'delta-down'
}
function hcpStatusType(s) {
  if (s === '已计算') return 'success'
  if (s === '样本不足') return 'warning'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球员会馆 · 球员名册</span>
        <span class="panel-desc">差点 handicap 必须落在 0 ~ 54 之间（USGA 上限）；差点指数由「差点中心」重算写回，依据可追溯</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">球员数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">单差点(≤5)</div><div class="v">{{ rows.filter(r => r.handicap <= 5).length }}</div></div>
        <div class="stat"><div class="k">中差点(6~18)</div><div class="v">{{ rows.filter(r => r.handicap > 5 && r.handicap <= 18).length }}</div></div>
        <div class="stat"><div class="k">高差点(>18)</div><div class="v">{{ rows.filter(r => r.handicap > 18).length }}</div></div>
        <div class="stat"><div class="k">已出指数</div><div class="v">{{ Object.values(summaries).filter(s => s.status === '已计算').length }}</div></div>
        <div class="stat"><div class="k">样本不足</div><div class="v">{{ Object.values(summaries).filter(s => s.status === '样本不足').length }}</div></div>
      </div>

      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建球员</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="playerNo" label="球员编号" width="110" />
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column prop="handicap" label="差点" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.handicap)">{{ row.handicap }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差点指数" width="100">
          <template #default="{ row }">
            <span v-if="row.handicapIndex != null" class="hcp-index">{{ fmt(row.handicapIndex) }}</span>
            <el-tag v-else-if="summaries[row.id]?.status === '样本不足'" type="warning" size="small">样本不足</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近变化" width="100">
          <template #default="{ row }">
            <span :class="changeClass(summaries[row.id]?.changeAmount)">
              {{ changeText(summaries[row.id]?.changeAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="warning" plain @click="openEdit(row)">编辑资料</el-button>
            <el-button size="small" type="primary" plain @click="openHandicap(row)">差点详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑球员资料' : '新建球员'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="dialog-form">
        <el-form-item label="球员编号" prop="playerNo">
          <el-input v-model="form.playerNo" placeholder="如 P-011" maxlength="16" show-word-limit />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" maxlength="48" show-word-limit />
        </el-form-item>
        <el-form-item label="差点(0~54)"><el-input v-model="form.handicap" type="number" min="0" max="54" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" maxlength="24" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" maxlength="64" /></el-form-item>
      </el-form>
      <div v-if="formError" class="err" style="margin-top:4px">{{ formError }}</div>
      <div v-if="editing" class="muted" style="margin-top:6px;font-size:12px">
        基于版本 v{{ form.version }} 编辑：他人在此期间提交后，本次保存会被拒绝并要求按最新资料重新核对。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存修改' : '建档' }}</el-button>
      </template>
    </el-dialog>

    <!-- 差点详情：当前指数 / 最近变化 / 采用与排除轮次 / 对应规则 / 历史追溯 -->
    <el-dialog v-model="hcpDialog" :title="`差点详情 · ${hcp?.playerName || ''}`" width="860px">
      <div v-loading="hcpLoading">
        <template v-if="hcp">
          <div class="stat-row">
            <div class="stat"><div class="k">当前指数</div>
              <div class="v">{{ hcp.status === '已计算' ? fmt(hcp.currentIndex) : '—' }}</div></div>
            <div class="stat"><div class="k">状态</div>
              <div class="v"><el-tag :type="hcpStatusType(hcp.status)">{{ hcp.status }}</el-tag></div></div>
            <div class="stat"><div class="k">最近变化</div>
              <div class="v" :class="changeClass(hcp.changeAmount)">{{ changeText(hcp.changeAmount) }}</div></div>
            <div class="stat"><div class="k">采用 / 样本</div>
              <div class="v">{{ hcp.usedCount ?? '—' }} / {{ hcp.sampleSize ?? '—' }}</div></div>
            <div class="stat"><div class="k">手工差点</div>
              <div class="v">{{ hcp.baseHandicap }}</div></div>
          </div>

          <div v-if="hcp.status !== '未计算'" class="hcp-meta">
            <span>规则：<b>{{ hcp.ruleName }}</b>（{{ hcp.ruleEffectiveDate }} 起生效）</span>
            <span>基准日：{{ hcp.asOfDate }}</span>
            <span>计算时间：{{ hcp.calculatedAt }}</span>
            <el-tag v-if="hcp.dropLimited" type="danger" size="small">下调受限</el-tag>
            <el-tag v-if="hcp.missingParams" type="warning" size="small">含缺参轮次</el-tag>
          </div>
          <div v-if="hcp.remark" class="muted" style="margin-bottom:10px">{{ hcp.remark }}</div>
          <div v-if="hcp.status === '未计算'" class="muted" style="margin-bottom:10px">
            该球员还没有差点计算记录，点击右下角「重算该球员」或到差点中心发起全量重算。
          </div>

          <template v-if="hcp.rounds && hcp.rounds.length">
            <div class="section-title">采用与排除的轮次（最近窗口内）</div>
            <el-table :data="hcp.rounds" size="small" border stripe max-height="260">
              <el-table-column prop="tournamentName" label="赛事" min-width="120" />
              <el-table-column prop="playDate" label="日期" width="105" />
              <el-table-column prop="roundNo" label="轮次" width="60" />
              <el-table-column prop="grossScore" label="总杆" width="60" />
              <el-table-column label="难度参数" width="110">
                <template #default="{ row }">
                  <span v-if="row.courseRating != null">{{ row.courseRating }} / {{ row.slopeRating }}</span>
                  <el-tag v-else type="danger" size="small">缺参数</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="差值" width="80">
                <template #default="{ row }">{{ row.differential == null ? '—' : fmt(row.differential) }}</template>
              </el-table-column>
              <el-table-column label="结果" min-width="180">
                <template #default="{ row }">
                  <el-tag v-if="row.used" type="success" size="small">采用</el-tag>
                  <el-tag v-else type="info" size="small">排除</el-tag>
                  <el-tag v-if="row.capped" type="warning" size="small" style="margin-left:4px">已截断</el-tag>
                  <span v-if="row.exclusionReason" class="muted" style="margin-left:6px">{{ row.exclusionReason }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="hcpHistory.length">
            <div class="section-title">历史计算（按当时规则可追溯）</div>
            <el-table :data="hcpHistory" size="small" border stripe max-height="220">
              <el-table-column prop="calculatedAt" label="计算时间" width="165" />
              <el-table-column prop="asOfDate" label="基准日" width="100" />
              <el-table-column prop="ruleName" label="规则" min-width="120" />
              <el-table-column label="指数" width="80">
                <template #default="{ row }">{{ row.indexValue == null ? '—' : fmt(row.indexValue) }}</template>
              </el-table-column>
              <el-table-column label="变化" width="80">
                <template #default="{ row }">
                  <span :class="changeClass(row.changeAmount)">{{ changeText(row.changeAmount) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="hcpStatusType(row.status)" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
            </el-table>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="hcpDialog = false">关闭</el-button>
        <el-button type="primary" :loading="recalcing" :disabled="!hcp" @click="recalcThis">重算该球员</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hcp-index { font-weight: 800; color: var(--primary-deep); font-size: 15px; }
.hcp-meta { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.section-title { font-size: 13px; font-weight: 700; color: var(--primary-deep); margin: 12px 0 8px; }
</style>
