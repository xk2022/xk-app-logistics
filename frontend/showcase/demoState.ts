import type { EditorDraft, EditorItem, EditorTask } from '../src/orderEditorModel'
import { toOrderDraft } from '../src/orderEditorModel'
export type DemoRole='ADMIN'|'DISPATCHER'|'DRIVER'
export type DemoPhase='UNASSIGNED'|'WAITING'|'DEPARTED'|'IN_PROGRESS'|'AWAITING_COMPLETION'|'COMPLETED'
export interface DemoPhoto {id:string;phase:DemoPhase;requestId:string|null}
export interface DemoException {id:string;description:string;status:'OPEN'|'NEEDS_SUPPLEMENT'|'RESOLVED';request:{id:string;reason:string;evidenceIds:string[]}|null;resolution:string|null;submittedEvidenceIds:string[];history:string[]}
export interface DemoTask {id:string;no:string;clientRef:string;source:EditorTask;item:EditorItem;phase:DemoPhase;driverId:string|null;mainVehicleId:string|null;trailerId:string|null;photos:DemoPhoto[];completionEvidenceIds:string[];exceptions:DemoException[];confirmed:boolean;history:string[]}
export interface DemoOrder {id:string;no:string;draft:EditorDraft;accepted:boolean;closed:boolean;tasks:DemoTask[];history:string[]}
export interface DemoState {orders:DemoOrder[];nextId:number;notice:string}
export class DemoError extends Error {}
export const personas:Record<DemoRole,string>={ADMIN:'合成管理員',DISPATCHER:'合成調度員',DRIVER:'合成司機'}
export const demoDrivers=[{id:'driver-1',name:'合成司機'},{id:'driver-2',name:'合成支援司機'}]
export const demoVehicles=[{id:'main-1',name:'DEMO-MAIN-01 · 合成主車',kind:'MAIN'},{id:'main-2',name:'DEMO-MAIN-02 · 合成主車',kind:'MAIN'},{id:'trailer-1',name:'DEMO-TRAILER-01 · 合成拖板',kind:'TRAILER'}]
export type DemoCommand=
 |{kind:'save';orderId:string|null;draft:EditorDraft}|{kind:'accept';orderId:string}
 |{kind:'assign';taskId:string;driverId:string;mainVehicleId:string;trailerId:string|null}
 |{kind:'depart';taskId:string}|{kind:'photo';taskId:string}|{kind:'progress';taskId:string;evidenceIds:string[]}
 |{kind:'report';taskId:string;text:string}|{kind:'request';taskId:string;exceptionId:string;text:string}
 |{kind:'supplementPhoto';taskId:string;exceptionId:string}|{kind:'submit';taskId:string;exceptionId:string;text:string;evidenceIds:string[]}
 |{kind:'resolve';taskId:string;exceptionId:string;text:string}
 |{kind:'complete';taskId:string;evidenceIds:string[];safeRelease:boolean}|{kind:'confirm';taskId:string;text:string}|{kind:'close';orderId:string;text:string}
function demand(condition:unknown,message:string):asserts condition {if(!condition)throw new DemoError(message)}
const nonblank=(text:string)=>text.trim().length>0
const sameSet=(a:string[],b:string[])=>a.length===b.length&&new Set(a).size===a.length&&a.every(id=>b.includes(id))
export function taskOccupied(task:DemoTask){return !['UNASSIGNED','COMPLETED'].includes(task.phase)}
export function allTasks(state:DemoState){return state.orders.flatMap(order=>order.tasks)}
export function executeDemo(original:DemoState,role:DemoRole,command:DemoCommand):DemoState {
 const s=structuredClone(original),manager=role==='ADMIN'||role==='DISPATCHER',fresh=(prefix:string)=>`${prefix}-${s.nextId++}`
 const managerKinds=['save','accept','assign','request','resolve','confirm','close']
 demand(managerKinds.includes(command.kind)?manager:role==='DRIVER','目前展示角色無法執行這項操作。請切換適合的展示角色。')
 const done=(text:string)=>{s.notice=text;return s}
 if(command.kind==='save'){
  toOrderDraft(command.draft)
  if(command.orderId){const order=s.orders.find(v=>v.id===command.orderId);demand(order&&!order.accepted&&!order.closed,'只有未受理草稿可以編輯。');order.draft=structuredClone(command.draft);order.history.push('保存合成草稿')}
  else {const id=fresh('order');s.orders.unshift({id,no:`DEMO-${command.draft.type}-${s.nextId}`,draft:structuredClone(command.draft),accepted:false,closed:false,tasks:[],history:['建立合成草稿']})}
  return done('展示草稿已保存；尚未受理或派工。')
 }
 if(command.kind==='accept'){
  const order=s.orders.find(v=>v.id===command.orderId);demand(order&&!order.accepted&&!order.closed,'此訂單已受理或目前不可受理。');toOrderDraft(order.draft)
  demand(order.draft.items.length>0&&order.draft.tasks.length>0,'請至少提供一個項目與一段任務。')
  for(const item of order.draft.items){
   demand(nonblank(item.sequence),'請補項目序號。');demand(item.itemKind==='CONTAINER'||item.itemKind==='CARGO','請明確選擇每個項目的標的類型。')
   if(item.itemKind==='CONTAINER'){demand(nonblank(item.containerType),'請補貨櫃類型。');if(order.draft.type==='IMPORT')demand(nonblank(item.lastPickupDate),'進口貨櫃請補最後提櫃日。');if(order.draft.type==='EXPORT')demand(nonblank(item.deliveryCutoffAt),'出口貨櫃請補交櫃截止時間。')}
   if(order.draft.type==='LOCAL'&&item.itemKind==='CARGO')demand(nonblank(item.packageQty),'國內一般貨物請補件數。')
   demand(order.draft.tasks.some(task=>task.itemRef===item.clientRef&&task.requiredTask==='true'),'每個項目至少須有一段明確標為必要的任務。')
  }
  if(order.draft.type==='IMPORT'||order.draft.type==='EXPORT'){
   const shipping=order.draft.shipping;demand(shipping&&[shipping.shippingCompany,shipping.vesselVoyage,shipping.pol,shipping.pod].every(nonblank),'請補船公司、船名航次、裝貨港與卸貨港。')
   if(order.draft.type==='IMPORT')demand(nonblank(shipping.eta)&&nonblank(shipping.blNo),'進口請補預計到港時間與提單號。');else demand(nonblank(shipping.etd),'出口請補預計離港時間。')
  }
  for(const task of order.draft.tasks)demand(task.itemRef&&order.draft.items.some(i=>i.clientRef===task.itemRef)&&[task.taskKind,task.pickupName,task.pickupAddress,task.deliveryName,task.deliveryAddress].every(nonblank),'請補齊每段任務的標的、作業類型與起訖名稱／地址。')
  const refs=new Set(order.draft.tasks.map(t=>t.clientRef));demand(refs.size===order.draft.tasks.length,'任務參照不可重複。')
  for(const task of order.draft.tasks)demand(task.predecessorRefs.every(ref=>refs.has(ref)&&ref!==task.clientRef),'前置任務必須存在，且不能指向自己。')
  const visited=new Set<string>(),visiting=new Set<string>();const visit=(ref:string)=>{demand(!visiting.has(ref),'前置任務不能形成循環。');if(visited.has(ref))return;visiting.add(ref);order.draft.tasks.find(t=>t.clientRef===ref)!.predecessorRefs.forEach(visit);visiting.delete(ref);visited.add(ref)};order.draft.tasks.forEach(t=>visit(t.clientRef))
  order.accepted=true;order.tasks=order.draft.tasks.map((source,index)=>({id:fresh('task'),no:order.id==='order-local'?`T-DEMO-${1001+index}`:`T-${order.no}-${index+1}`,clientRef:source.clientRef,source:structuredClone(source),item:structuredClone(order.draft.items.find(i=>i.clientRef===source.itemRef)!),phase:'UNASSIGNED',driverId:null,mainVehicleId:null,trailerId:null,photos:[],completionEvidenceIds:[],exceptions:[],confirmed:false,history:['展示受理已建立任務來源快照']}));order.history.push('展示受理完成');return done('展示受理完成；這是瀏覽器記憶體中的合成事實。')
 }
 if(command.kind==='close'){
  const order=s.orders.find(v=>v.id===command.orderId);demand(order&&order.accepted&&!order.closed,'此訂單目前不可結案。');demand(nonblank(command.text),'請填寫結案原因。');demand(order.tasks.length>0&&order.tasks.every(t=>t.phase==='COMPLETED'&&t.confirmed&&t.exceptions.every(e=>e.status==='RESOLVED')),'本單所有任務必須已完成、無未處置異常，並逐一取得管理確認。');order.closed=true;order.history.push(`展示結案：${command.text}`);return done('展示訂單已結案；不代表正式系統交易。')
 }
 const order=s.orders.find(o=>o.tasks.some(t=>t.id===command.taskId)),task=order?.tasks.find(t=>t.id===command.taskId);demand(order&&task&&!order.closed,'找不到目前可操作的展示任務。')
 if(!manager)demand(task.driverId==='driver-1','本人運輸任務只可操作派給合成司機的任務。')
 if(command.kind==='assign'){
  demand(task.phase==='UNASSIGNED','同一任務已有派工，不能重複指派。');demand(task.source.predecessorRefs.every(ref=>order.tasks.some(t=>t.clientRef===ref&&t.phase==='COMPLETED')),'前置任務尚未完成，暫不能指派。');demand(demoDrivers.some(d=>d.id===command.driverId),'請選擇展示司機。');demand(demoVehicles.some(v=>v.id===command.mainVehicleId&&v.kind==='MAIN'),'請選擇合成主車。');demand(command.trailerId===null||demoVehicles.some(v=>v.id===command.trailerId&&v.kind==='TRAILER'),'請選擇有效拖板或不使用拖板。');demand(!allTasks(s).some(t=>taskOccupied(t)&&(t.driverId===command.driverId||t.mainVehicleId===command.mainVehicleId||Boolean(command.trailerId&&t.trailerId===command.trailerId))),'選取的司機或車輛已有有效占用。完成任務後才會釋放。');task.driverId=command.driverId;task.mainVehicleId=command.mainVehicleId;task.trailerId=command.trailerId;task.phase='WAITING';task.history.push('展示派工已建立');return done('展示派工已建立。切換「合成司機」可繼續本人任務。')
 }
 if(command.kind==='depart'){demand(task.phase==='WAITING','只有待出發任務可以回報出發。');task.phase='DEPARTED';task.history.push('展示出發回報');return done('展示任務已出發；尚未提交執行進度。')}
 if(command.kind==='photo'){demand(['DEPARTED','IN_PROGRESS'].includes(task.phase),'普通任務照片只能在已出發或執行中加入。');task.photos.push({id:fresh('photo'),phase:task.phase,requestId:null});task.history.push('加入內建合成任務照片');return done('內建合成照片已加入。照片與進度提交是兩個步驟。')}
 if(command.kind==='progress'){demand(['DEPARTED','IN_PROGRESS'].includes(task.phase),'目前沒有下一段可提交的進度。');demand(command.evidenceIds.length>0&&new Set(command.evidenceIds).size===command.evidenceIds.length&&command.evidenceIds.every(id=>task.photos.some(p=>p.id===id&&p.phase===task.phase&&p.requestId===null)),'請明確選取本階段的普通合成照片。');task.phase=task.phase==='DEPARTED'?'IN_PROGRESS':'AWAITING_COMPLETION';if(task.phase==='AWAITING_COMPLETION')task.completionEvidenceIds=[...command.evidenceIds];task.history.push(`展示進度：${task.phase}`);return done('展示進度已提交；任務仍未完成。')}
 if(command.kind==='report'){demand(['DEPARTED','IN_PROGRESS','AWAITING_COMPLETION'].includes(task.phase),'目前階段不能回報異常。');demand(nonblank(command.text),'請填寫異常描述。');task.exceptions.push({id:fresh('exception'),description:command.text,status:'OPEN',request:null,resolution:null,submittedEvidenceIds:[],history:[`本人回報：${command.text}`]});return done('展示異常已回報；完成前須由管理端處置。')}
 if(command.kind==='complete'){demand(task.phase==='AWAITING_COMPLETION','任務必須先到待完成確認。');demand(task.exceptions.every(e=>e.status==='RESOLVED'),'仍有未處置異常，暫不能完成。');demand(command.safeRelease===true,'請明確確認任務完成與解除資源占用。');demand(task.completionEvidenceIds.length>0&&sameSet(command.evidenceIds,task.completionEvidenceIds),'完成必須沿用最近一次進度提交的完整照片集合。');task.phase='COMPLETED';task.history.push('展示任務完成；有效資源占用已釋放');return done('展示任務已完成，資源可再次使用；尚未完成管理確認或訂單結案。')}
 if(command.kind==='confirm'){demand(task.phase==='COMPLETED'&&!task.confirmed,'只有尚未管理確認的已完成任務可以確認。');demand(nonblank(command.text),'請填寫管理確認原因。');task.confirmed=true;task.history.push(`展示管理確認：${command.text}`);return done('管理確認已記錄；訂單結案仍是另一個步驟。')}
 const record=task.exceptions.find(e=>e.id===command.exceptionId);demand(record,'找不到這個任務的異常紀錄。')
 if(command.kind==='request'){demand(record.status==='OPEN','只有待管理處置的異常可以要求補件。');demand(nonblank(command.text),'請填寫司機可見的補件要求。');record.status='NEEDS_SUPPLEMENT';record.submittedEvidenceIds=[];record.request={id:fresh('request'),reason:command.text,evidenceIds:[]};record.history.push(`要求補件：${command.text}`);return done('展示補件要求已建立。請切換合成司機加入並提交補件。')}
 if(command.kind==='resolve'){demand(record.status==='OPEN'&&record.request===null&&record.submittedEvidenceIds.length>0,'須先取得司機明確提交的本次補件，才能管理處置；不能以處置豁免補件。');demand(nonblank(command.text),'請填寫司機可見的處置原因。');record.status='RESOLVED';record.resolution=command.text;record.request=null;record.history.push(`管理處置：${command.text}`);return done('展示異常已處置；不會自動完成任務。')}
 demand(record.status==='NEEDS_SUPPLEMENT'&&record.request,'目前沒有有效的補件要求。');demand(['DEPARTED','IN_PROGRESS','AWAITING_COMPLETION'].includes(task.phase),'目前階段不能操作補件。')
 if(command.kind==='supplementPhoto'){const id=fresh('photo');task.photos.push({id,phase:task.phase,requestId:record.request.id});record.request.evidenceIds.push(id);record.history.push('加入本次要求的內建合成補件照片');return done('內建合成補件照片已加入，仍需明確提交。')}
 demand(nonblank(command.text),'請填寫補件說明。');demand(command.evidenceIds.length>0&&new Set(command.evidenceIds).size===command.evidenceIds.length&&command.evidenceIds.every(id=>record.request!.evidenceIds.includes(id)),'請只選取這一次補件要求的照片。');record.submittedEvidenceIds=[...command.evidenceIds];record.status='OPEN';record.request=null;record.history.push(`本人補件提交：${command.text}`);return done('展示補件已提交，狀態回到待管理處置；尚未解除異常阻擋。')
}
