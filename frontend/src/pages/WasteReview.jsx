import React, { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import { Card, Stats, Badge, fmt, fmtTime, RESPONSIBILITY } from '../ui.jsx'

export default function WasteReview() {
  const [data, setData] = useState(null)
  const [cause, setCause] = useState('')

  useEffect(() => { api.get('/api/analytics/waste-review').then(setData).catch(() => {}) }, [])

  const detail = useMemo(() => (data?.detail || []).filter((d) => !cause || d.cause === cause), [data, cause])
  const maxAmount = Math.max(1, ...(data?.summary || []).map((s) => s.amount))
  const colors = { SPEC: '#7c3aed', DESIGN: '#1d4ed8', CONSTRUCTION: '#dc2626', STORAGE: '#d97706', QUALITY: '#92400e', OTHER: '#64748b' }

  return (
    <div>
      <Stats items={(data?.summary || []).map((s) => ({
        label: s.causeName,
        value: fmt(s.amount) + ' 元',
        sub: `${s.count} 笔`,
        tone: s.amount > 0 ? 'red' : 'green'
      })).concat([
        { label: '损耗总额', value: fmt(data?.totalAmount) + ' 元', tone: 'brand' }
      ])} />

      <Card title="损耗原因结构（月底复盘）" hint="区分损耗来源：规格领错 / 设计变更 / 施工错误 / 保管不当 / 供应商质量">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {(data?.summary || []).map((s) => (
            <div key={s.cause} style={{ display: 'grid', gridTemplateColumns: '110px 1fr 130px 50px', gap: 12, alignItems: 'center' }}>
              <b>{s.causeName}</b>
              <div className="bar" style={{ height: 20 }}>
                <i style={{
                  width: `${Math.max(s.amount ? 3 : 0, s.amount / maxAmount * 100)}%`,
                  background: colors[s.cause]
                }} />
              </div>
              <div className="num"><b>{fmt(s.amount)}</b> 元</div>
              <div className="muted">{s.count} 笔</div>
            </div>
          ))}
        </div>
        <div className="kpi-note" style={{ marginTop: 16 }}>
          复盘结论建议：<br />
          · <b>规格领错</b>已切割部分按责任归属：仓管发错不扣班组，班组申报错误计入班组扣款，同班组再领同材料系统自动预警；<br />
          · <b>设计变更</b>导致的拆除/报废 → 走变更签证向业主索赔，不计班组责任；<br />
          · <b>施工错误/瓷砖破损</b> → 班组责任，纳入月度扣款并加强交底；<br />
          · <b>保管不当/砂浆过期</b> → 材料员与班组共同整改堆放与领用节奏（随用随领）；<br />
          · <b>供应商质量</b>（如补货卷材厚度不达标）→ 批次判废、补货延迟记录，结算时扣款/索赔。
        </div>
      </Card>

      <Card title="损耗明细台账" flush
            actions={
              <div className="filters">
                <div className="f">
                  <label>原因归类</label>
                  <select value={cause} onChange={(e) => setCause(e.target.value)}>
                    <option value="">全部</option>
                    {(data?.summary || []).map((s) => <option key={s.cause} value={s.cause}>{s.causeName}</option>)}
                  </select>
                </div>
              </div>
            }>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>单号</th><th>材料</th><th>楼栋/工序</th><th>责任班组</th>
              <th className="num">数量</th><th className="num">金额</th><th>复盘归类</th>
              <th>责任归属</th><th>经过说明</th><th>时间</th>
            </tr></thead>
            <tbody>
            {detail.map((d, i) => (
              <tr key={i}>
                <td className="code-link">{d.code}</td>
                <td><b>{d.materialName}</b></td>
                <td>{d.building}<div className="muted" style={{ fontSize: 12 }}>{d.process}</div></td>
                <td>{d.crewName || '—'}</td>
                <td className="num">{fmt(d.qty)}</td>
                <td className="num pos">{fmt(d.amount)}</td>
                <td><Badge status="DRAFT" label={d.causeName} tone="blue" /></td>
                <td><Badge status="DRAFT" label={RESPONSIBILITY[d.responsibility] || d.responsibility} tone="gray" /></td>
                <td className="desc-cell">{d.description}</td>
                <td>{fmtTime(d.createdTime)}</td>
              </tr>
            ))}
            {detail.length === 0 && <tr><td colSpan={10}><div className="empty">暂无该类损耗</div></td></tr>}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
