<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const summaries = ref({})
const error = ref('')
const tip = ref('')
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)

const form = ref(blank())
function blank() {
  return { playerNo: '', name: '', handicap: 10, phone: '', note: '' }
}

// ---------------- 球员编辑（必填资料服务端校验 + 乐观版本） ----------------
// dialogMode: create / edit。编辑时 formVersion 记录打开的版本号，随 PUT 一起发；
// 他人先改导致 409 时，不允许本地覆盖，明确提示「数据已变化」并强制重新拉取当前球员。
const dialogMode = ref('create')
const formVersion = ref(null)
const dialogError = ref('')
const dialogTitle = computed(() => dialogMode.value === 'edit' ? '编辑球员' : '新建球员')
const formRules = {
  playerNo: [{ required: true, whitespace: true, message: '球员编号不能为空', trigger: 'blur' }],
  name: [{ required: true, whitespace: true, message: '球员姓名不能为空', trigger: 'blur' }]
}
const formRef = ref(null)

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
  dialogMode.value = 'create'
  formVersion.value = null
  form.value = { id: null, ...blank() }
  dialogError.value = ''
  dialog.value = true
  tip.value = ''; error.value = ''
}

/**
 * 打开编辑：以服务端返回的当前行作为表单初值并记录版本号，不做本地拼装，
 * 避免用过期 / 残缺数据发起更新。
 */
function openEdit(row) {
  dialogMode.value = 'edit'
  formVersion.value = row.version
  form.value = {
    id: row.id,
    playerNo: row.playerNo ?? '',
    name: row.name ?? '',
    handicap: row.handicap ?? 10,
    phone: row.phone ?? '',
    note: row.note ?? ''
  }
  dialogError.value = ''
  dialog.value = true
  tip.value = ''; error.value = ''
}

/**
 * 保存（新建 / 编辑共用）。
 * - 前端必填校验只是第一道，真正口径在服务端（trim 后非空）；400 时弹窗保留、原资料不动。
 * - 409 版本过期：关闭弹窗、明确提示「数据已变化」，用服务端最新数据重建列表，
 *   过期表单绝不写回；管理员需重新点编辑、基于当前版本改。
 * - 成功后从服务端整表重载（球员名册 + 差点汇总），列表 / 详情 / 赛事页的球员显示
 *   都经接口取数，不存在只改页面局部状态导致的新旧姓名不一致。
 */
async function save() {
  dialogError.value = ''
  try {
    await formRef.value?.validate?.()
  } catch {
    dialogError.value = '请先补全必填项：球员编号、姓名均不能为空（纯空格也不行）'
    return
  }
  saving.value = true
  try {
    const d = {
      ...form.value,
      playerNo: String(form.value.playerNo ?? ''),
      name: String(form.value.name ?? ''),
      handicap: Number(form.value.handicap)
    }
    if (dialogMode.value === 'edit') {
      await api.updatePlayer(form.value.id, d, formVersion.value)
      tip.value = `球员 ${d.name.trim()} 的资料已更新（列表、详情与赛事相关显示已同步为最新资料）`
      dialog.value = false
    } else {
      await api.createPlayer(d)
      tip.value = `球员 ${d.name.trim()} 已建档`
      dialog.value = false
    }
    await load()
  } catch (e) {
    if (e.isConflict) {
      // 他人已先提交：本次修改未生效，关闭过期表单并按最新数据刷新，给出明确的核对提示。
      dialog.value = false
      error.value = `${e.message}（本次修改未生效，列表已按最新数据刷新，请重新打开编辑）`
      await load()
    } else {
      // 400 校验失败：服务端已保留原资料，弹窗保留用户输入，错误明确可核对。
      dialogError.value = e.message
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
        <el-table-column label="版本" width="80">
          <template #default="{ row }"><span class="muted">v{{ row.version ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="primary" plain @click="openHandicap(row)">差点详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="dialogTitle" width="560px">
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="110px"
        class="dialog-form"
        @submit.prevent
      >
        <div v-if="dialogError" class="err dialog-err">{{ dialogError }}</div>
        <el-alert
          v-if="dialogMode === 'edit'"
          type="info"
          :closable="false"
          show-icon
          class="version-tip"
          title="多人同时编辑时，只有基于最新版本的一次提交会生效；若他人已先修改，提交会提示数据已变化并要求重新读取。"
        >
          当前基于版本 v{{ formVersion ?? 0 }} 编辑
        </el-alert>
        <el-form-item label="球员编号" prop="playerNo" required>
          <el-input v-model="form.playerNo" placeholder="如 P-011（必填，纯空格无效）" />
        </el-form-item>
        <el-form-item label="姓名" prop="name" required>
          <el-input v-model="form.name" placeholder="必填，服务端会去除首尾空格后校验" />
        </el-form-item>
        <el-form-item label="差点(0~54)"><el-input v-model="form.handicap" type="number" min="0" max="54" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ dialogMode === 'edit' ? '保存修改' : '建档' }}
        </el-button>
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
.dialog-err { margin-bottom: 12px; }
.version-tip { margin-bottom: 14px; }
</style>
