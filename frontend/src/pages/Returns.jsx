import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta, RETURN_REASON, CONDITION, FAULT_TYPE, RESPONSIBILITY } from '../ui.jsx'

/* 照片压缩：最长边 960px 转 JPEG，控制 dataURL 体积 */
function fileToPhoto(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const img = new Image()
      img.onload = () => {
        try {
          const max = 960
          const scale = Math.min(1, max / Math.max(img.width, img.height))
          const canvas = document.createElement('canvas')
          canvas.width = Math.max(1, Math.round(img.width * scale))
          canvas.height = Math.max(1, Math.round(img.height * scale))
          canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height)
          resolve({ name: file.name, dataUrl: canvas.toDataURL('image/jpeg', 0.75) })
        } catch {
          resolve({ name: file.name, dataUrl: reader.result })
        }
      }
      img.onerror = () => resolve({ name: file.name, dataUrl: reader.result })
      img.src = reader.result
    }
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

export default function Returns() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_FOREMAN', 'ROLE_KEEPER', 'ROLE_PM'].includes(user.role)
  const canConfirm = ['ROLE_KEEPER', 'ROLE_PM'].includes(user.role)

  const [rows, setRows] = useState([])
  const [status, setStatus] = useState('')
  const [show, setShow] = useState(false)
  const [detail, setDetail] = useState(null)
  const [photoView, setPhotoView] = useState(null)
  const [confirmTarget, setConfirmTarget] = useState(null)
  const [fault, setFault] = useState({ faultType: 'CREW', remark: '' })
  const blank = () => ({ materialId: '', batchId: '', qty: '', cutQty: '', conditionType: 'GOOD', price: '' })
  const [f, setF] = useState({
    crewId: user.crewId || '', requisitionId: '', building: '1#楼', area: '', process: '',
    reasonType: 'SURPLUS', remark: '', photos: [], items: [blank()]
  })
  const [reqOptions, setReqOptions] = useState([])
  const specWrong = f.reasonType === 'SPEC_WRONG'

  const load = () => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    if (user.role === 'ROLE_FOREMAN') q.set('crewId', user.crewId)
    api.get('/api/returns?' + q.toString()).then(setRows)
  }
  useEffect(() => { load() }, [status])
  useEffect(() => {
    if (show) api.get('/api/requisitions?status=ISSUED').then(setReqOptions).catch(() => {})
  }, [show])

  const batchesOf = (materialId) => {
    // 通过批次列表拉取（未专门做按材料过滤接口，前端筛选）
    return window.__batchesCache?.filter((b) => b.materialId === Number(materialId)) || []
  }
  const [batchCache, setBatchCache] = useState([])
  useEffect(() => { if (show) api.get('/api/batches').then((d) => { setBatchCache(d); window.__batchesCache = d }) }, [show])

  const addPhotos = async (files) => {
    const rest = 9 - f.photos.length
    if (rest <= 0) return toast('现场照片最多 9 张', 'err')
    const picked = [...files].slice(0, rest)
    const photos = await Promise.all(picked.map(fileToPhoto))
    setF((prev) => ({ ...prev, photos: [...prev.photos, ...photos] }))
  }

  const submit = async () => {
    if (!f.crewId || f.items.some((i) => !i.materialId || (!Number(i.qty) && !Number(i.cutQty)))) {
      return toast('请完善班组与退库明细（未使用与已切割不能同时为 0）', 'err')
    }
    if (specWrong && f.photos.length === 0) return toast('规格领错退换需上传现场照片', 'err')
    try {
      await api.post('/api/returns', {
        ...f,
        crewId: Number(f.crewId),
        requisitionId: f.requisitionId ? Number(f.requisitionId) : null,
        items: f.items.map((i) => ({
          materialId: Number(i.materialId),
          batchId: i.batchId ? Number(i.batchId) : null,
          qty: Number(i.qty) || 0,
          cutQty: Number(i.cutQty) || 0,
          conditionType: i.conditionType,
          price: i.price === '' ? undefined : Number(i.price)
        }))
      })
      toast('退库单已提交，等待仓管确认')
      setShow(false); setF({ ...f, items: [blank()], photos: [] }); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const confirm = async (r) => {
    if (r.reasonType === 'SPEC_WRONG') {
      setFault({ faultType: 'CREW', remark: '' })
      setConfirmTarget(r)
      return
    }
    try {
      await api.put(`/api/returns/${r.id}/confirm`, {})
      toast('退库已确认，完好材料回补批次库存')
      setDetail(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const confirmSpecWrong = async () => {
    try {
      const res = await api.put(`/api/returns/${confirmTarget.id}/confirm`, fault)
      const cut = res.totalCutAmount || 0
      toast(fault.faultType === 'CREW'
        ? `已确认：完好部分回补库存，切割损耗 ${fmt(cut)} 元计入该班组成本`
        : `已确认：完好部分回补库存，切割损耗 ${fmt(cut)} 元项目承担，班组不扣款`)
      setConfirmTarget(null); setDetail(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const reject = async (id) => {
    if (!window.confirm('确认驳回该退库单？')) return
    await api.put(`/api/returns/${id}/reject`, {}); toast('已驳回'); load()
  }

  return (
    <Card title="退库单" hint="临时追加多余 / 规格领错退换 / 施工变更退料；规格领错需上传现场照片，确认时判定责任：仓管发错班组不扣款，班组申请错误成本归班组"
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
            {canCreate && <button className="btn primary" onClick={() => setShow(true)}>＋ 办理退库</button>}
          </div>} flush>
      <div className="table-scroll">
        <table className="tbl">
          <thead><tr>
            <th>退库单号</th><th>班组</th><th>楼栋/工序</th><th>退库原因</th>
            <th>材料明细</th><th className="num">金额</th><th>状态</th>
            <th>申请人/确认人</th><th>操作</th>
          </tr></thead>
          <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td className="code-link" onClick={() => setDetail(r)}>{r.code}
                {r.photos?.length > 0 && <span className="tag-mini" style={{ marginLeft: 6 }}>📷{r.photos.length}</span>}
              </td>
              <td>{r.crewName}</td>
              <td>{r.building}<div className="muted" style={{ fontSize: 12 }}>{r.process}</div></td>
              <td>
                <Badge status="DRAFT" label={RETURN_REASON[r.reasonType]} tone="blue" />
                {r.faultType && (
                  <div style={{ marginTop: 4 }}>
                    <Badge status="DRAFT" label={FAULT_TYPE[r.faultType]} tone={r.faultType === 'CREW' ? 'red' : 'green'} />
                  </div>
                )}
              </td>
              <td style={{ fontSize: 12.5 }}>
                {r.items.map((i) => (
                  <div key={i.materialId}>{i.materialName} 退×{fmt(i.qty)}
                    {i.cutQty > 0 && <span className="tag-mini" style={{ marginLeft: 6 }}>切×{fmt(i.cutQty)}</span>}
                    {i.conditionType === 'DAMAGED' && <span className="tag-mini" style={{ marginLeft: 6 }}>损坏</span>}
                  </div>
                ))}
              </td>
              <td className="num">{fmt(r.totalAmount)}
                {r.totalCutAmount > 0 && <div className="muted" style={{ fontSize: 12 }}>损耗 {fmt(r.totalCutAmount)}</div>}
              </td>
              <td><Badge status={r.status} /></td>
              <td style={{ fontSize: 12 }}>{r.applicant}<div className="muted">{r.confirmedBy || '—'}</div></td>
              <td>
                {r.status === 'SUBMITTED' && canConfirm && (
                  <div className="btn-row">
                    <button className="btn sm primary" onClick={() => confirm(r)}>确认入库</button>
                    <button className="btn sm danger" onClick={() => reject(r.id)}>驳回</button>
                  </div>
                )}
                {r.status !== 'SUBMITTED' && <button className="btn sm ghost" onClick={() => setDetail(r)}>查看</button>}
              </td>
            </tr>
          ))}
          </tbody>
        </table>
      </div>

      {show && (
        <Modal title="办理退库" wide onClose={() => setShow(false)}
               footer={<><button className="btn" onClick={() => setShow(false)}>取消</button>
                 <button className="btn primary" onClick={submit}>提交退库单</button></>}>
          <div className="grid3">
            <Field label="班组 *">
              <select value={f.crewId} disabled={user.role === 'ROLE_FOREMAN'} onChange={(e) => setF({ ...f, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="楼栋 *"><input value={f.building} onChange={(e) => setF({ ...f, building: e.target.value })} /></Field>
            <Field label="工序 *"><input value={f.process} onChange={(e) => setF({ ...f, process: e.target.value })} /></Field>
            <Field label="施工区域"><input value={f.area} onChange={(e) => setF({ ...f, area: e.target.value })} /></Field>
            <Field label="关联领料单">
              <select value={f.requisitionId} onChange={(e) => setF({ ...f, requisitionId: e.target.value })}>
                <option value="">无（无单退库）</option>
                {reqOptions.filter((r) => !f.crewId || r.crewId === Number(f.crewId))
                  .map((r) => <option key={r.id} value={r.id}>{r.code} · {r.process}</option>)}
              </select>
            </Field>
            <Field label="退库原因 *">
              <select value={f.reasonType} onChange={(e) => setF({ ...f, reasonType: e.target.value })}>
                {Object.entries(RETURN_REASON).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </Field>
          </div>
          <Field label="备注"><input value={f.remark} onChange={(e) => setF({ ...f, remark: e.target.value })}
            placeholder="规格领错/施工变更/临时追加的具体说明" /></Field>

          {specWrong && (
            <div className="warn-banner">
              <b>规格领错退换：</b>请分行填写<b>未使用数量</b>（确认后重新入库）与<b>已切割数量</b>（确认后计入损耗），
              并上传现场照片；仓管确认时判定责任 —— 仓管发错班组不扣款，班组申请错误成本归班组。
            </div>
          )}

          <div style={{ margin: '8px 0 6px', fontWeight: 600 }}>
            退库明细{specWrong ? '（未使用数量回补库存，已切割数量计损耗）' : '（损坏退回请选择"损坏(班组责任)"，不回补库存）'}
          </div>
          <table className="tbl">
            <thead><tr><th>材料</th><th style={{ width: 150 }}>退回批次</th>
              <th style={{ width: 96 }}>{specWrong ? '未使用数量' : '数量'}</th>
              {specWrong && <th style={{ width: 96 }}>已切割数量</th>}
              <th style={{ width: 150 }}>完好程度</th>
              <th style={{ width: 100 }}>单价</th><th style={{ width: 60 }}></th></tr></thead>
            <tbody>
            {f.items.map((it, idx) => {
              const set = (patch) => {
                const items = f.items.slice()
                items[idx] = { ...items[idx], ...patch }
                setF({ ...f, items })
              }
              return (
                <tr key={idx}>
                  <td>
                    <select value={it.materialId} onChange={(e) => set({ materialId: e.target.value, batchId: '' })}>
                      <option value="">请选择</option>
                      {meta.materials.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                    </select>
                  </td>
                  <td>
                    <select value={it.batchId} onChange={(e) => set({ batchId: e.target.value })}>
                      <option value="">自动匹配批次</option>
                      {batchesOf(it.materialId).map((b) =>
                        <option key={b.id} value={b.id}>{b.batchNo}｜库存{fmt(b.onHand)}</option>)}
                    </select>
                  </td>
                  <td><input type="number" value={it.qty} onChange={(e) => set({ qty: e.target.value })} /></td>
                  {specWrong && <td><input type="number" value={it.cutQty} onChange={(e) => set({ cutQty: e.target.value })} /></td>}
                  <td>
                    <select value={it.conditionType} onChange={(e) => set({ conditionType: e.target.value })}>
                      {Object.entries(CONDITION).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                  </td>
                  <td><input type="number" placeholder="取批次价" value={it.price} onChange={(e) => set({ price: e.target.value })} /></td>
                  <td>{f.items.length > 1 && <button className="btn sm danger"
                    onClick={() => setF({ ...f, items: f.items.filter((_, i) => i !== idx) })}>删</button>}</td>
                </tr>
              )
            })}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }} onClick={() => setF({ ...f, items: [...f.items, blank()] })}>＋ 增加材料</button>

          {specWrong && (
            <div style={{ marginTop: 14 }}>
              <div style={{ fontWeight: 600, marginBottom: 6 }}>现场照片 *（最多 9 张，自动压缩）</div>
              <input type="file" accept="image/*" multiple
                     onChange={(e) => { addPhotos(e.target.files); e.target.value = '' }} />
              <div className="photo-row">
                {f.photos.map((p, idx) => (
                  <div className="photo-cell" key={idx}>
                    <img src={p.dataUrl} alt={p.name} onClick={() => setPhotoView(p)} />
                    <button className="del" onClick={() => setF({ ...f, photos: f.photos.filter((_, i) => i !== idx) })}>✕</button>
                    <div className="nm">{p.name}</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </Modal>
      )}

      {detail && (
        <Modal title={`退库单 ${detail.code}`} wide onClose={() => setDetail(null)}
               footer={<><button className="btn" onClick={() => setDetail(null)}>关闭</button>
                 {detail.status === 'SUBMITTED' && canConfirm &&
                   <button className="btn primary" onClick={() => confirm(detail)}>确认入库</button>}</>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            {detail.crewName} · {detail.building} {detail.area} · {detail.process} ·
            原因：<b>{RETURN_REASON[detail.reasonType]}</b>
            {detail.faultType && <> · 责任判定：<b>{FAULT_TYPE[detail.faultType]}</b>
              {detail.reasonType === 'SPEC_WRONG' && (detail.faultType === 'CREW' ? '（切割损耗归班组）' : '（班组不扣款）')}</>}
            · 状态：<Badge status={detail.status} />
            <div style={{ marginTop: 6 }}>{detail.remark}</div>
          </div>
          <table className="tbl">
            <thead><tr><th>材料</th><th>批次</th><th className="num">未使用(回补)</th>
              <th className="num">已切割(损耗)</th><th>完好程度</th><th className="num">单价</th><th className="num">金额</th></tr></thead>
            <tbody>
            {detail.items.map((i) => (
              <tr key={i.materialId}>
                <td>{i.materialName}</td>
                <td>#{i.batchId || '自动'}</td>
                <td className="num">{fmt(i.qty)} {i.unit}</td>
                <td className="num">{i.cutQty > 0 ? <span className="pos">{fmt(i.cutQty)} {i.unit}</span> : '—'}</td>
                <td>{i.conditionType === 'GOOD'
                  ? <Badge status="PASSED" label="完好，回补库存" />
                  : <Badge status="FAILED" label="损坏，班组责任" />}</td>
                <td className="num">{fmt(i.price)}</td>
                <td className="num">{fmt(i.amount)}
                  {i.cutAmount > 0 && <div className="muted" style={{ fontSize: 12 }}>损耗 {fmt(i.cutAmount)}</div>}
                </td>
              </tr>
            ))}
            </tbody>
          </table>
          {detail.wasteRefs?.length > 0 && (
            <div className="kpi-note" style={{ marginTop: 10 }}>
              已切割部分自动生成损耗单：
              {detail.wasteRefs.map((w) => (
                <span key={w.code} className="tag-mini" style={{ marginRight: 8 }}>
                  {w.code} · {fmt(w.qty)} · {RESPONSIBILITY[w.responsibility]}
                </span>
              ))}
            </div>
          )}
          {detail.photos?.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 6 }}>现场照片（{detail.photos.length}）</div>
              <div className="photo-row">
                {detail.photos.map((p) => (
                  <div className="photo-cell" key={p.id}>
                    <img src={p.dataUrl} alt={p.name} onClick={() => setPhotoView(p)} />
                    <div className="nm">{p.name}</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </Modal>
      )}

      {/* 规格领错：仓管确认 + 责任判定 */}
      {confirmTarget && (
        <Modal title={`确认退换 · ${confirmTarget.code}`} onClose={() => setConfirmTarget(null)}
               footer={<><button className="btn" onClick={() => setConfirmTarget(null)}>取消</button>
                 <button className="btn primary" onClick={confirmSpecWrong}>确认退换完成</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            {confirmTarget.crewName} · {confirmTarget.building} {confirmTarget.area} · {confirmTarget.process}
          </div>
          <table className="tbl" style={{ marginBottom: 12 }}>
            <thead><tr><th>材料</th><th className="num">未使用(重新入库)</th><th className="num">已切割(计入损耗)</th></tr></thead>
            <tbody>
            {confirmTarget.items.map((i) => (
              <tr key={i.materialId}>
                <td>{i.materialName}</td>
                <td className="num">{fmt(i.qty)} {i.unit}</td>
                <td className="num">{i.cutQty > 0 ? `${fmt(i.cutQty)} ${i.unit}` : '—'}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <Field label="责任判定 *（决定切割损耗与材料成本归属）">
            <select value={fault.faultType} onChange={(e) => setFault({ ...fault, faultType: e.target.value })}>
              <option value="CREW">班组申请错误 —— 成本归入该班组</option>
              <option value="WAREHOUSE">仓管发错 —— 班组不扣款（项目承担）</option>
            </select>
          </Field>
          <Field label="确认备注"><input value={fault.remark} onChange={(e) => setFault({ ...fault, remark: e.target.value })}
            placeholder="如：核对图纸电施-401，确认为申请规格写错" /></Field>
          <div className="warn-banner" style={{ marginBottom: 0 }}>
            确认后：未使用部分<b>重新入库</b>并冲减班组消耗；已切割部分自动生成<b>切割损耗单</b>，
            {fault.faultType === 'CREW' ? '计入该班组月度核算扣款' : '由项目承担，班组不扣款'}；
            该班组下次申请相同材料时将提示曾领错规格。
          </div>
        </Modal>
      )}

      {/* 照片放大查看 */}
      {photoView && (
        <Modal title={photoView.name || '现场照片'} onClose={() => setPhotoView(null)}
               footer={<button className="btn" onClick={() => setPhotoView(null)}>关闭</button>}>
          <img className="photo-view" src={photoView.dataUrl} alt={photoView.name} />
        </Modal>
      )}
    </Card>
  )
}
