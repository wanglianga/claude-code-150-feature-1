import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta, EXCHANGE_RESP } from '../ui.jsx'

/** 图片压缩为最大宽度 1024 的 JPEG dataURI，避免现场照片超过后端 3MB/张限制 */
function fileToDataUri(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = reject
    reader.onload = () => {
      const img = new Image()
      img.onerror = reject
      img.onload = () => {
        const maxW = 1024
        const scale = Math.min(1, maxW / img.width)
        const canvas = document.createElement('canvas')
        canvas.width = Math.round(img.width * scale)
        canvas.height = Math.round(img.height * scale)
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height)
        resolve(canvas.toDataURL('image/jpeg', 0.72))
      }
      img.src = reader.result
    }
    reader.readAsDataURL(file)
  })
}

export default function SpecExchanges() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_FOREMAN', 'ROLE_KEEPER', 'ROLE_PM'].includes(user.role)
  const canConfirm = ['ROLE_KEEPER', 'ROLE_PM'].includes(user.role)

  const [rows, setRows] = useState([])
  const [status, setStatus] = useState('')
  const [show, setShow] = useState(false)
  const [detail, setDetail] = useState(null)
  const [resp, setResp] = useState('KEEPER')
  const [faultNote, setFaultNote] = useState('')
  const [reqOptions, setReqOptions] = useState([])
  const [batchCache, setBatchCache] = useState([])

  const blank = () => ({
    crewId: user.crewId || '', requisitionId: '', building: '1#楼', area: '标准层', process: '机电预埋',
    materialId: '', batchId: '', wrongSpec: '', expectSpec: '',
    issuedQty: '', unusedQty: '', cutQty: '', remark: '', photos: []
  })
  const [f, setF] = useState(blank())

  const load = () => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    if (user.role === 'ROLE_FOREMAN') q.set('crewId', user.crewId)
    api.get('/api/spec-exchanges?' + q.toString()).then(setRows)
  }
  useEffect(() => { load() }, [status])
  useEffect(() => {
    if (show) {
      api.get('/api/requisitions?status=ISSUED').then(setReqOptions).catch(() => {})
      api.get('/api/batches').then(setBatchCache).catch(() => {})
    }
  }, [show])

  const mat = (id) => meta.materials.find((m) => m.id === Number(id))
  const batchesOf = (materialId) => batchCache.filter((b) => b.materialId === Number(materialId))

  const pickMaterial = (materialId) => {
    const m = mat(materialId)
    setF({ ...f, materialId, batchId: '', wrongSpec: m?.spec || '' })
  }

  const pickRequisition = (reqId) => {
    const r = reqOptions.find((x) => String(x.id) === String(reqId))
    if (!r) return setF({ ...f, requisitionId: '' })
    const first = r.items[0]
    setF({
      ...f,
      requisitionId: r.id,
      building: r.building, area: r.area || f.area, process: r.process,
      materialId: first.materialId,
      wrongSpec: mat(first.materialId)?.spec || '',
      issuedQty: first.issuedQty || first.applyQty || ''
    })
  }

  const onPhotos = async (files) => {
    const remain = 3 - f.photos.length
    if (files.length > remain) toast(`最多 3 张现场照片，已取前 ${remain} 张`, 'err')
    for (const file of Array.from(files).slice(0, remain)) {
      try {
        const uri = await fileToDataUri(file)
        setF((prev) => ({ ...prev, photos: [...prev.photos, uri] }))
      } catch { toast('照片读取失败', 'err') }
    }
  }

  const submit = async () => {
    if (!f.crewId || !f.materialId) return toast('请选择班组与领错规格的材料', 'err')
    if (!f.unusedQty && !f.cutQty) return toast('请至少填写未使用数量或已切割数量', 'err')
    if (Number(f.unusedQty) + Number(f.cutQty) > Number(f.issuedQty))
      return toast('未使用+已切割数量不能超过领出数量', 'err')
    if (f.photos.length === 0) return toast('请至少上传 1 张现场照片', 'err')
    try {
      await api.post('/api/spec-exchanges', {
        crewId: Number(f.crewId),
        requisitionId: f.requisitionId ? Number(f.requisitionId) : null,
        building: f.building, area: f.area, process: f.process,
        materialId: Number(f.materialId),
        batchId: f.batchId ? Number(f.batchId) : null,
        wrongSpec: f.wrongSpec, expectSpec: f.expectSpec,
        issuedQty: Number(f.issuedQty), unusedQty: Number(f.unusedQty), cutQty: Number(f.cutQty),
        photos: f.photos, remark: f.remark
      })
      toast('退换申请已提交，等待仓管现场核对并判定责任')
      setShow(false); setF(blank()); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const confirm = async () => {
    try {
      const d = await api.put(`/api/spec-exchanges/${detail.id}/confirm`, {
        responsibility: resp, faultNote
      })
      toast(d.responsibility === 'KEEPER'
        ? `已确认：${fmt(d.returnAmount)} 元可退部分重新入库，切割 ${fmt(d.cutQty)} ${d.unit} 计损耗，班组不扣款`
        : `已确认：${fmt(d.returnAmount)} 元重新入库，切割 ${fmt(d.cutQty)} ${d.unit} 成本归入该班组`)
      setDetail(null); load()
    } catch (e) { toast(e.message, 'err') }
  }
  const reject = async (id) => {
    if (!window.confirm('确认驳回该退换申请？')) return
    try { await api.put(`/api/spec-exchanges/${id}/reject`, {}); toast('已驳回'); setDetail(null); load() }
    catch (e) { toast(e.message, 'err') }
  }

  return (
    <Card title="规格领错退换料"
          hint="现场发现规格不对无法安装：班组长上报未使用数量、已切割数量与现场照片；仓管确认后可退部分重新入库、切割部分计损耗，并判定责任方"
          actions={<div className="filters">
            <div className="f">
              <label>状态</label>
              <select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">全部</option>
                <option value="SUBMITTED">待确认</option>
                <option value="CONFIRMED">已确认</option>
                <option value="REJECTED">已驳回</option>
              </select>
            </div>
            {canCreate && <button className="btn primary" onClick={() => { setF(blank()); setShow(true) }}>＋ 发起退换</button>}
          </div>} flush>
      <div className="table-scroll">
        <table className="tbl">
          <thead><tr>
            <th>退换单号</th><th>班组</th><th>楼栋/工序</th><th>领错材料</th>
            <th className="num">领出</th><th className="num">未使用(可退)</th><th className="num">已切割(损耗)</th>
            <th>照片</th><th>责任判定</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td className="code-link" onClick={() => { setDetail(r); setResp(r.responsibility || 'KEEPER'); setFaultNote(r.faultNote || '') }}>{r.code}</td>
              <td>{r.crewName}</td>
              <td>{r.building}<div className="muted" style={{ fontSize: 12 }}>{r.process}</div></td>
              <td>
                <b>{r.materialName}</b>
                <div className="muted" style={{ fontSize: 12 }}>
                  实收 <span style={{ color: '#b03a2e' }}>{r.wrongSpec}</span>
                  {r.expectSpec && <> / 需用 <span style={{ color: '#15803d' }}>{r.expectSpec}</span></>}
                </div>
              </td>
              <td className="num">{fmt(r.issuedQty)} {r.unit}</td>
              <td className="num" style={{ color: '#15803d' }}>{fmt(r.unusedQty)}</td>
              <td className="num pos">{fmt(r.cutQty)}</td>
              <td>
                <div style={{ display: 'flex', gap: 4 }}>
                  {r.photos.map((p, i) => (
                    <img key={i} src={p} alt="现场照片" title="点击查看"
                         onClick={() => { setDetail(r); setResp(r.responsibility || 'KEEPER'); setFaultNote(r.faultNote || '') }}
                         style={{ width: 40, height: 28, objectFit: 'cover', borderRadius: 4, cursor: 'pointer', border: '1px solid #ddd' }} />
                  ))}
                  {r.photos.length === 0 && <span className="muted">—</span>}
                </div>
              </td>
              <td>{r.responsibility
                ? <Badge status={r.responsibility === 'KEEPER' ? 'PASSED' : 'FAILED'}
                         label={r.responsibility === 'KEEPER' ? '仓管发错·不扣班组' : '班组申请错误'} />
                : <span className="muted" style={{ fontSize: 12 }}>待判定</span>}</td>
              <td><Badge status={r.status} /></td>
              <td>
                {r.status === 'SUBMITTED' && canConfirm && (
                  <div className="btn-row">
                    <button className="btn sm primary"
                            onClick={() => { setDetail(r); setResp('KEEPER'); setFaultNote('') }}>核对确认</button>
                    <button className="btn sm danger" onClick={() => reject(r.id)}>驳回</button>
                  </div>
                )}
                {r.status !== 'SUBMITTED' &&
                  <button className="btn sm ghost"
                          onClick={() => { setDetail(r); setResp(r.responsibility || 'KEEPER'); setFaultNote(r.faultNote || '') }}>查看</button>}
              </td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={11}><div className="empty">暂无退换记录</div></td></tr>}
          </tbody>
        </table>
      </div>

      {/* 发起退换 */}
      {show && (
        <Modal title="发起规格领错退换" wide onClose={() => setShow(false)}
               footer={<><button className="btn" onClick={() => setShow(false)}>取消</button>
                 <button className="btn primary" onClick={submit}>提交退换申请</button></>}>
          <div className="grid3">
            <Field label="班组 *">
              <select value={f.crewId} disabled={user.role === 'ROLE_FOREMAN'}
                      onChange={(e) => setF({ ...f, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="楼栋 *"><input value={f.building} onChange={(e) => setF({ ...f, building: e.target.value })} /></Field>
            <Field label="工序 *"><input value={f.process} onChange={(e) => setF({ ...f, process: e.target.value })} /></Field>
            <Field label="施工区域"><input value={f.area} onChange={(e) => setF({ ...f, area: e.target.value })} /></Field>
            <Field label="关联原领料单">
              <select value={f.requisitionId} onChange={(e) => pickRequisition(e.target.value)}>
                <option value="">无（手工登记）</option>
                {reqOptions.filter((r) => !f.crewId || r.crewId === Number(f.crewId))
                  .map((r) => <option key={r.id} value={r.id}>{r.code} · {r.process}</option>)}
              </select>
            </Field>
            <Field label="领错的材料 *">
              <select value={f.materialId} onChange={(e) => pickMaterial(e.target.value)}>
                <option value="">请选择</option>
                {meta.materials.map((m) => <option key={m.id} value={m.id}>
                  [{m.category}] {m.name}（{m.spec}）</option>)}
              </select>
            </Field>
            <Field label="实收（错误）规格"><input value={f.wrongSpec}
              onChange={(e) => setF({ ...f, wrongSpec: e.target.value })} placeholder="如 DN20" /></Field>
            <Field label="现场需要规格"><input value={f.expectSpec}
              onChange={(e) => setF({ ...f, expectSpec: e.target.value })} placeholder="如 DN25" /></Field>
            <Field label="来源批次（可退部分回补）">
              <select value={f.batchId} onChange={(e) => setF({ ...f, batchId: e.target.value })}>
                <option value="">自动匹配批次</option>
                {batchesOf(f.materialId).map((b) =>
                  <option key={b.id} value={b.id}>{b.batchNo}｜库存{fmt(b.onHand)}</option>)}
              </select>
            </Field>
            <Field label="领出数量 *"><input type="number" value={f.issuedQty}
              onChange={(e) => setF({ ...f, issuedQty: e.target.value })} /></Field>
            <Field label="未使用数量（可退）*"><input type="number" value={f.unusedQty}
              onChange={(e) => setF({ ...f, unusedQty: e.target.value })} /></Field>
            <Field label="已切割数量（损耗）*"><input type="number" value={f.cutQty}
              onChange={(e) => setF({ ...f, cutQty: e.target.value })} /></Field>
          </div>
          <Field label="情况说明"><input value={f.remark}
            onChange={(e) => setF({ ...f, remark: e.target.value })}
            placeholder="如：标准层强电回路线管规格与图纸不符，现场无法安装" /></Field>

          <div style={{ margin: '10px 0 6px', fontWeight: 600 }}>现场照片（最多 3 张，至少 1 张）</div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            {f.photos.map((p, i) => (
              <div key={i} style={{ position: 'relative' }}>
                <img src={p} alt="照片" style={{ width: 140, height: 96, objectFit: 'cover', borderRadius: 6, border: '1px solid #ddd' }} />
                <button type="button" className="btn sm danger"
                        style={{ position: 'absolute', top: 4, right: 4, padding: '0 6px' }}
                        onClick={() => setF({ ...f, photos: f.photos.filter((_, x) => x !== i) })}>✕</button>
              </div>
            ))}
            {f.photos.length < 3 && (
              <label className="btn" style={{ width: 140, height: 96, display: 'flex', alignItems: 'center',
                justifyContent: 'center', cursor: 'pointer', borderStyle: 'dashed' }}>
                ＋ 上传照片
                <input type="file" accept="image/*" multiple style={{ display: 'none' }}
                       onChange={(e) => onPhotos(e.target.files)} />
              </label>
            )}
          </div>
          <div className="kpi-note" style={{ marginTop: 10 }}>
            口径：未使用部分经仓管核对后重新入库、回补可用库存；已切割部分无法再用，计入损耗。
            仓管发错不扣班组；班组申请错误，切割成本归入该班组月度核算。
          </div>
        </Modal>
      )}

      {/* 详情 / 仓管确认 */}
      {detail && (
        <Modal title={`规格退换单 ${detail.code}`} wide onClose={() => setDetail(null)}
               footer={<><button className="btn" onClick={() => setDetail(null)}>关闭</button>
                 {detail.status === 'SUBMITTED' && canConfirm && <>
                   <button className="btn danger" onClick={() => reject(detail.id)}>驳回</button>
                   <button className="btn primary" onClick={confirm}>确认退换并更新库存/成本</button>
                 </>}</>}>
          <div className="kpi-note" style={{ marginBottom: 10, lineHeight: 1.9 }}>
            {detail.crewName} · {detail.building} {detail.area} · {detail.process}<br />
            领错材料：<b>{detail.materialName}</b>，实收规格 <b style={{ color: '#b03a2e' }}>{detail.wrongSpec}</b>
            {detail.expectSpec && <>，现场需用 <b style={{ color: '#15803d' }}>{detail.expectSpec}</b></>}<br />
            领出 {fmt(detail.issuedQty)} {detail.unit} ｜ 未使用可退 <b style={{ color: '#15803d' }}>{fmt(detail.unusedQty)} {detail.unit}</b>
            （{fmt(detail.returnAmount)} 元）｜ 已切割损耗 <b className="pos">{fmt(detail.cutQty)} {detail.unit}</b>
            （{fmt(detail.cutAmount)} 元）<br />
            状态：<Badge status={detail.status} />
            {detail.responsibility && <>　责任：<b>{EXCHANGE_RESP[detail.responsibility]}</b></>}
            {detail.remark && <><br />说明：{detail.remark}</>}
          </div>

          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
            {detail.photos.map((p, i) => (
              <a key={i} href={p} target="_blank" rel="noreferrer">
                <img src={p} alt={`现场照片${i + 1}`}
                     style={{ width: 200, height: 140, objectFit: 'cover', borderRadius: 6, border: '1px solid #ddd' }} />
              </a>
            ))}
            {detail.photos.length === 0 && <div className="muted">无现场照片</div>}
          </div>

          {detail.status === 'SUBMITTED' && canConfirm && (
            <div className="grid3" style={{ alignItems: 'end' }}>
              <Field label="责任判定 *">
                <select value={resp} onChange={(e) => setResp(e.target.value)}>
                  {Object.entries(EXCHANGE_RESP).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </Field>
              <Field label="判定说明"><input value={faultNote} onChange={(e) => setFaultNote(e.target.value)}
                placeholder="如：相邻规格货位拿错 / 班组未按图纸申报" /></Field>
              <div className="kpi-note">
                {resp === 'KEEPER'
                  ? '切割损耗计项目承担，班组不扣款；可退部分重新入库。'
                  : '切割损耗计入该班组责任损耗，月底核算进扣款；可退部分重新入库。'}
              </div>
            </div>
          )}
          {detail.status === 'CONFIRMED' && (
            <div className="kpi-note">
              确认人：{detail.confirmedBy}　时间：{fmtTime(detail.confirmTime)}
              {detail.faultNote && <>　判定说明：{detail.faultNote}</>}<br />
              已生成切割损耗记录 #{detail.wasteId || '—'}；下次该班组申请「{detail.materialName}」时平台将提示曾领错规格。
            </div>
          )}
        </Modal>
      )}
    </Card>
  )
}
