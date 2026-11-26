import { ApiError, getCsrfHeaders, refreshCsrf } from './api'
import { readTomJson } from './tomApi'
import type { MyTask } from './tmsApi'
export type DriverExceptionStatus = 'OPEN' | 'NEEDS_SUPPLEMENT' | 'RESOLVED'
export type DriverReportedPhase = 'DEPARTED' | 'IN_PROGRESS' | 'AWAITING_COMPLETION'
export type DriverExecutionPhase = 'WAITING' | DriverReportedPhase | 'COMPLETED'
export type DriverMode = 'UNCONFIRMED' | 'SYNTHETIC_TEST_ONLY'
export interface DriverExecutionView { phase: DriverExecutionPhase; version: number; departedAt: string | null }
export interface DriverExceptionSummary { id: string; assignmentId: string; taskId: string; taskNo: string; reportedPhase: DriverReportedPhase; status: DriverExceptionStatus; version: number; blocking: boolean; reportedAt: string }
export interface DriverActiveRequest { id: string; reason: string; requestedAt: string; uploadedCount: number; evidenceIds: string[] }
export interface DriverExceptionRecord extends DriverExceptionSummary { reportedExecutionVersion: number; description: string; resolvedAt: string | null; resolutionNote: string | null; activeRequest: DriverActiveRequest | null }
export type DriverActionBlocker = 'FORBIDDEN' | 'POLICY_NOT_CONFIRMED' | 'INVALID_STATE' | 'SUPPLEMENT_REQUIRED' | 'EVIDENCE_REQUIRED'
export interface DriverExceptionAction { allowed: boolean; blocker: DriverActionBlocker | null }
export interface DriverEvidenceRequirements { minimumCount: 1; allowedMediaTypes: ['image/png']; maxBytes: 65536; maxPixels: 4096 }
export interface DriverExceptionActions { mode: DriverMode; requestSupplement: DriverExceptionAction; resolve: DriverExceptionAction; uploadSupplement: DriverExceptionAction; submitSupplement: DriverExceptionAction; requirements: DriverEvidenceRequirements | null }
export interface DriverExceptionDetail { exception: DriverExceptionRecord; execution: DriverExecutionView; actions: DriverExceptionActions }
export interface DriverPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type DriverExceptionEventType = 'REPORTED' | 'SUPPLEMENT_REQUESTED' | 'SUPPLEMENT_EVIDENCE_UPLOADED' | 'SUPPLEMENT_SUBMITTED' | 'RESOLVED'
export interface DriverExceptionEvent { id: string; exceptionId: string; type: DriverExceptionEventType; fromStatus: DriverExceptionStatus | null; toStatus: DriverExceptionStatus; fromVersion: number | null; toVersion: number; occurredAt: string; note: string | null; requestId: string | null; evidenceIds: string[] }
export interface DriverEvidence { id: string; assignmentId: string; taskId: string; phase: DriverExecutionPhase; executionVersion: number; mediaType: 'image/png'; byteSize: number; sha256: string; uploadedAt: string; status: 'VERIFIED' }
export interface DriverSuccess<T> { code: '0000'; message: string; data: T; timestamp: string }
export interface DriverPageQuery { page?: number; size?: number }
export type DriverTaskDetail = MyTask & { execution:DriverExecutionView;completion:DriverCompletionWitness|null }
export interface DriverCompletionWitness { id:string;completedAt:string;releasedAt:string;policyMode:'SYNTHETIC_TEST_ONLY';safeReleaseConfirmed:true }
export interface DriverExecutionResult { assignmentId:string;taskId:string;execution:DriverExecutionView }
export interface DriverCompleteResult extends DriverExecutionResult { completion:DriverCompletionWitness }
export interface DriverSupplementUploadResult { exceptionId:string;assignmentId:string;taskId:string;version:number;requestId:string;evidence:DriverEvidence }
export interface DriverProgressPolicy { assignmentId:string;taskId:string;execution:DriverExecutionView;mode:DriverMode;nextPhase:'IN_PROGRESS'|'AWAITING_COMPLETION'|null;requirements:DriverEvidenceRequirements|null;blocker:'POLICY_NOT_CONFIRMED'|'UNSUPPORTED_PHASE'|null }
export interface DriverCompletionPolicy { assignmentId:string;taskId:string;execution:DriverExecutionView;mode:DriverMode;canComplete:boolean;blocker:'POLICY_NOT_CONFIRMED'|'UNSUPPORTED_PHASE'|'UNRESOLVED_EXCEPTIONS'|'EVIDENCE_REQUIRED'|'EVIDENCE_INVALID'|'ALREADY_COMPLETED'|null;requirements:(DriverEvidenceRequirements & {evidenceBasis:'LATEST_AWAITING_TRANSITION';requiresSafeReleaseConfirmation:true})|null;requiredEvidenceIds:string[]|null }
export interface DriverExceptionQuery extends DriverPageQuery {status?:DriverExceptionStatus}
export type DriverCommandKind='depart'|'progress'|'upload'|'report'|'supplementUpload'|'supplementSubmit'|'complete'
export interface DriverTaskBaseline {assignmentId:string;taskId:string;taskNo:string;execution:DriverExecutionView}
export interface DriverSupplementBaseline {task:DriverTaskBaseline;exception:DriverExceptionRecord;requestId:string}
export interface DriverCommandScope {readonly __opaqueScope:unique symbol}
export interface DriverCommandIntent {readonly kind:DriverCommandKind;readonly __opaqueIntent:unique symbol}
export interface PreparedDriverPng {readonly mediaType:'image/png';readonly byteSize:number;readonly __opaquePng:unique symbol}
export interface DriverUnresolvedIndicator {readonly kind:DriverCommandKind;readonly retryable:false;readonly outcome:'UNKNOWN'}
export interface DriverCommandInputMap {
 depart:{expectedVersion:number};progress:{expectedVersion:number;targetPhase:'IN_PROGRESS'|'AWAITING_COMPLETION';evidenceIds:string[]};upload:{expectedVersion:number;photo:PreparedDriverPng};report:{expectedExecutionVersion:number;description:string};supplementUpload:{expectedVersion:number;expectedExecutionVersion:number;photo:PreparedDriverPng};supplementSubmit:{expectedVersion:number;note:string;evidenceIds:string[]};complete:{expectedVersion:number;evidenceIds:string[];confirmSafeRelease:true}
}
export interface DriverCommandReview {kind:DriverCommandKind;assignmentId:string;taskId:string;taskNo:string;expectedExecutionVersion:number;expectedExceptionVersion?:number;description?:string;note?:string;evidenceIds?:readonly string[];targetPhase?:string;photoBytes?:number;confirmSafeRelease?:true;exceptionId?:string;requestId?:string}
export type DriverCommandReceipt={kind:'depart'|'progress';data:DriverExecutionResult;replayed:boolean}|{kind:'upload';data:DriverEvidence;replayed:boolean}|{kind:'report'|'supplementSubmit';data:DriverExceptionRecord;replayed:boolean}|{kind:'supplementUpload';data:DriverSupplementUploadResult;replayed:boolean}|{kind:'complete';data:DriverCompleteResult;replayed:boolean}
export class DriverApiError extends ApiError {
 readonly reason:string|null;readonly actualVersion?:number;readonly actualExecutionVersion?:number;readonly uncertain:boolean
 constructor(status:number,public code:string,message:string,details:{reason?:string|null;actualVersion?:number;actualExecutionVersion?:number;fields?:Record<string,string>;uncertain?:boolean}={}) {super(status,message,details.fields??{});this.name='DriverApiError';this.reason=details.reason??null;this.actualVersion=details.actualVersion;this.actualExecutionVersion=details.actualExecutionVersion;this.uncertain=details.uncertain??false}
}
const max=Number.MAX_SAFE_INTEGER
const statusValues:DriverExceptionStatus[]=['OPEN','NEEDS_SUPPLEMENT','RESOLVED']
const phaseValues:DriverExecutionPhase[]=['WAITING','DEPARTED','IN_PROGRESS','AWAITING_COMPLETION','COMPLETED']
const summaryKeys=['id','assignmentId','taskId','taskNo','reportedPhase','status','version','blocking','reportedAt']
function decimal(v:unknown):v is string {return typeof v==='string'&&v.length<=32&&full(/^(0|[1-9][0-9]*)(\.[0-9]+)?$/,v)}
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function exact(value: unknown, keys: readonly string[]): value is Record<string, unknown> { return object(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key)) }
function full(pattern: RegExp, value: string) { return pattern.exec(value)?.[0] === value }
function uuid(value: unknown): value is string { return typeof value === 'string' && full(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, value) }
function canonical(value: unknown): value is string { return uuid(value) && value === value.toLowerCase() }
function integer(value: unknown, minimum = 0, upper = max): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= upper }
function member<T extends string>(value: unknown, values: readonly T[]): value is T { return values.includes(value as T) }
function unicode(value: string) { for (let index = 0; index < value.length; index++) { const code = value.charCodeAt(index); if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) return false } else if (code >= 0xdc00 && code <= 0xdfff) return false }; return true }
function text(value: unknown, limit: number, minimum = 0): value is string { return typeof value === 'string' && unicode(value) && [...value].length >= minimum && [...value].length <= limit }
export function nonblankDriverText(value: string) { return [...value].some((char) => !(/[\p{White_Space}\p{Zs}]/u.test(char) && char !== '\u0085' || char === '\uFEFF' || char.charCodeAt(0) >= 0x1c && char.charCodeAt(0) <= 0x1f)) }
function fact(value: unknown, limit: number): value is string { return text(value, limit, 1) && nonblankDriverText(value) }
function timestamp(value: unknown, precision = 9): value is string {
  if (typeof value !== 'string' || (value.match(/\.(\d+)/)?.[1].length ?? 0) > precision) return false
  const match = /^(\d{4})-(\d{2})-(\d{2})[Tt]([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.[0-9]{1,9})?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value)
  if (!match || match[0] !== value) return false
  const [year, month, day] = match.slice(1, 4).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
function nullable<T>(value: unknown, check: (entry: unknown) => entry is T): value is T | null { return value === null || check(value) }
function malformed(): never { throw new Error('Invalid exception response') }
function input(message: string, fields: Record<string, string> = {}): never { throw new DriverApiError(400, 'CLIENT_VALIDATION_ERROR', message, { reason: 'VALIDATION_ERROR', fields }) }
function identifiers(value: unknown): value is string[] { return Array.isArray(value) && value.length <= 32 && value.every(canonical) && value.every((id, index) => index === 0 || value[index - 1] < id) }
function parseSummary(value: unknown): DriverExceptionSummary {
  if (!exact(value, summaryKeys) || !['id', 'assignmentId', 'taskId'].every((key) => canonical(value[key])) || !text(value.taskNo, 100, 1) || !member(value.reportedPhase, ['DEPARTED', 'IN_PROGRESS', 'AWAITING_COMPLETION']) || !member(value.status, statusValues) || !integer(value.version) || value.version === 0 && value.status !== 'OPEN' || value.status === 'NEEDS_SUPPLEMENT' && value.version < 1 || value.blocking !== (value.status !== 'RESOLVED') || !timestamp(value.reportedAt)) malformed()
  return value as unknown as DriverExceptionSummary
}
function parseRequest(value: unknown): DriverActiveRequest {
  if (!exact(value, ['id', 'reason', 'requestedAt', 'uploadedCount', 'evidenceIds']) || !canonical(value.id) || !fact(value.reason, 1000) || !timestamp(value.requestedAt) || !integer(value.uploadedCount, 0, 32) || !identifiers(value.evidenceIds) || value.uploadedCount !== value.evidenceIds.length) malformed()
  return value as unknown as DriverActiveRequest
}
function parseRecord(value: unknown): DriverExceptionRecord {
  if (!exact(value, [...summaryKeys, 'reportedExecutionVersion', 'description', 'resolvedAt', 'resolutionNote', 'activeRequest'])) malformed()
  parseSummary(Object.fromEntries(summaryKeys.map((key) => [key, value[key]])))
  if (!integer(value.reportedExecutionVersion, 1) || !fact(value.description, 2000) || !nullable(value.resolvedAt, timestamp) || !nullable(value.resolutionNote, (entry): entry is string => fact(entry, 1000))) malformed()
  if (value.status === 'RESOLVED' ? value.resolvedAt === null || value.resolutionNote === null : value.resolvedAt !== null || value.resolutionNote !== null) malformed()
  if (value.status === 'NEEDS_SUPPLEMENT' ? value.activeRequest === null : value.activeRequest !== null) malformed()
  return { ...value, activeRequest: value.activeRequest === null ? null : parseRequest(value.activeRequest) } as unknown as DriverExceptionRecord
}
function parseExecution(value: unknown): DriverExecutionView {
  if (!exact(value, ['phase', 'version', 'departedAt']) || !member(value.phase, phaseValues) || !integer(value.version) || !nullable(value.departedAt, timestamp) || (value.phase === 'WAITING' ? value.version !== 0 || value.departedAt !== null : value.version < 1 || value.departedAt === null)) malformed()
  return value as unknown as DriverExecutionView
}
function parseAction(value: unknown): DriverExceptionAction {
  if (!exact(value, ['allowed', 'blocker']) || typeof value.allowed !== 'boolean' || !nullable(value.blocker, (entry): entry is DriverActionBlocker => member(entry, ['FORBIDDEN', 'POLICY_NOT_CONFIRMED', 'INVALID_STATE', 'SUPPLEMENT_REQUIRED', 'EVIDENCE_REQUIRED'])) || value.allowed !== (value.blocker === null)) malformed()
  return value as unknown as DriverExceptionAction
}
function parseItem(value: unknown): MyTask['item'] {
  if (!exact(value, ['itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm']) || !member(value.itemKind, ['CONTAINER', 'CARGO']) || !['containerType', 'containerNo'].every((key) => nullable(value[key], (v): v is string => text(v, 200))) || !nullable(value.packageQty, (v): v is number => integer(v, 1, 2147483647)) || !nullable(value.grossWeight, decimal) || !nullable(value.cbm, decimal)) malformed()
  return value as unknown as MyTask['item']
}
function parseContact(value: unknown): MyTask['siteContact'] | null { if (value === null) return null; if (!exact(value, ['name', 'phone']) || !nullable(value.name, (v): v is string => text(v, 200)) || !nullable(value.phone, (v): v is string => text(v, 200))) malformed(); return value as unknown as MyTask['siteContact'] }
function routeFields(value: Record<string, unknown>): boolean {
  return ['taskKind', 'pickupName', 'deliveryName'].every((key) => nullable(value[key], (v): v is string => text(v, 200))) && ['pickupAddress', 'deliveryAddress'].every((key) => nullable(value[key], (v): v is string => text(v, 1000))) && nullable(value.plannedStartAt, (v): v is string => timestamp(v, 6)) && nullable(value.plannedEndAt, (v): v is string => timestamp(v, 6))
}

function parseTask(value:unknown,id:string):DriverTaskDetail {
 if(!exact(value,['assignmentId','taskId','taskNo','item','taskKind','pickupName','pickupAddress','deliveryName','deliveryAddress','siteContact','plannedStartAt','plannedEndAt','assignedAt','policyMode','execution','completion'])||!canonical(value.assignmentId)||value.assignmentId!==id||!canonical(value.taskId)||!text(value.taskNo,100,1)||!routeFields(value)||!timestamp(value.assignedAt)||!member(value.policyMode,['UNCONFIRMED','SYNTHETIC_TEST_ONLY']))malformed()
 const execution=parseExecution(value.execution),completion=value.completion===null?null:parseCompletion(value.completion)
 if(execution.phase==='COMPLETED'?(execution.version<4||completion===null):completion!==null)malformed()
 return {...value,item:parseItem(value.item),siteContact:parseContact(value.siteContact),execution,completion} as unknown as DriverTaskDetail
}
function parseCompletion(v:unknown):DriverCompletionWitness {if(!exact(v,['id','completedAt','releasedAt','policyMode','safeReleaseConfirmed'])||!canonical(v.id)||!timestamp(v.completedAt)||v.releasedAt!==v.completedAt||v.policyMode!=='SYNTHETIC_TEST_ONLY'||v.safeReleaseConfirmed!==true)malformed();return v as unknown as DriverCompletionWitness}
function parseRequirements(v:unknown):DriverEvidenceRequirements {if(!exact(v,['minimumCount','allowedMediaTypes','maxBytes','maxPixels'])||v.minimumCount!==1||!Array.isArray(v.allowedMediaTypes)||v.allowedMediaTypes.length!==1||v.allowedMediaTypes[0]!=='image/png'||v.maxBytes!==65536||v.maxPixels!==4096)malformed();return v as unknown as DriverEvidenceRequirements}
function scopeIds(v:Record<string,unknown>,assignment:string,task?:string) {if(v.assignmentId!==assignment||!canonical(v.taskId)||task!==undefined&&v.taskId!==task)malformed()}
function parseEvidence(v:unknown,assignment:string,task?:string):DriverEvidence {if(!exact(v,['id','assignmentId','taskId','phase','executionVersion','mediaType','byteSize','sha256','uploadedAt','status'])||!canonical(v.id)||!member(v.phase,['WAITING','DEPARTED','IN_PROGRESS','AWAITING_COMPLETION','COMPLETED'])||!integer(v.executionVersion,1)||v.mediaType!=='image/png'||!integer(v.byteSize,1)||typeof v.sha256!=='string'||!full(/^[0-9a-f]{64}$/,v.sha256)||!timestamp(v.uploadedAt)||v.status!=='VERIFIED')malformed();scopeIds(v,assignment,task);return v as unknown as DriverEvidence}
function parseProgressPolicy(v:unknown,id:string):DriverProgressPolicy {if(!exact(v,['assignmentId','taskId','execution','mode','nextPhase','requirements','blocker'])||!member(v.mode,['UNCONFIRMED','SYNTHETIC_TEST_ONLY'])||!nullable(v.blocker,(x):x is string=>member(x,['POLICY_NOT_CONFIRMED','UNSUPPORTED_PHASE'])))malformed();scopeIds(v,id);const execution=parseExecution(v.execution)
 if(v.blocker===null){if(v.mode!=='SYNTHETIC_TEST_ONLY'||!['DEPARTED','IN_PROGRESS'].includes(execution.phase)||v.nextPhase!==(execution.phase==='DEPARTED'?'IN_PROGRESS':'AWAITING_COMPLETION'))malformed();parseRequirements(v.requirements)}else if(v.nextPhase!==null||v.requirements!==null||v.mode==='UNCONFIRMED'&&v.blocker!=='POLICY_NOT_CONFIRMED'||v.blocker==='UNSUPPORTED_PHASE'&&(v.mode!=='SYNTHETIC_TEST_ONLY'||!['WAITING','AWAITING_COMPLETION','COMPLETED'].includes(execution.phase)))malformed()
 return {...v,execution} as unknown as DriverProgressPolicy
}
function parseCompletionPolicy(v:unknown,id:string):DriverCompletionPolicy {if(!exact(v,['assignmentId','taskId','execution','mode','canComplete','blocker','requirements','requiredEvidenceIds'])||!member(v.mode,['UNCONFIRMED','SYNTHETIC_TEST_ONLY'])||typeof v.canComplete!=='boolean'||!nullable(v.blocker,(x):x is string=>member(x,['POLICY_NOT_CONFIRMED','UNSUPPORTED_PHASE','UNRESOLVED_EXCEPTIONS','EVIDENCE_REQUIRED','EVIDENCE_INVALID','ALREADY_COMPLETED']))||v.canComplete!==(v.blocker===null))malformed();scopeIds(v,id);const execution=parseExecution(v.execution)
 if(v.canComplete){if(v.mode!=='SYNTHETIC_TEST_ONLY'||execution.phase!=='AWAITING_COMPLETION'||execution.version<3||!exact(v.requirements,['minimumCount','allowedMediaTypes','maxBytes','maxPixels','evidenceBasis','requiresSafeReleaseConfirmation'])||v.requirements.evidenceBasis!=='LATEST_AWAITING_TRANSITION'||v.requirements.requiresSafeReleaseConfirmation!==true||!identifiers(v.requiredEvidenceIds)||v.requiredEvidenceIds.length<1)malformed();const {evidenceBasis,requiresSafeReleaseConfirmation,...rules}=v.requirements;void evidenceBasis;void requiresSafeReleaseConfirmation;parseRequirements(rules)}else if(v.requirements!==null||v.requiredEvidenceIds!==null)malformed()
 if(execution.phase==='COMPLETED'?(execution.version<4||v.blocker!=='ALREADY_COMPLETED'):v.blocker==='ALREADY_COMPLETED'||v.mode==='UNCONFIRMED'&&v.blocker!=='POLICY_NOT_CONFIRMED')malformed()
 if(v.blocker==='UNSUPPORTED_PHASE'&&(v.mode!=='SYNTHETIC_TEST_ONLY'||!['WAITING','DEPARTED','IN_PROGRESS'].includes(execution.phase)))malformed()
 if(['UNRESOLVED_EXCEPTIONS','EVIDENCE_REQUIRED','EVIDENCE_INVALID'].includes(String(v.blocker))&&(v.mode!=='SYNTHETIC_TEST_ONLY'||execution.phase!=='AWAITING_COMPLETION'||execution.version<3))malformed()
 return {...v,execution} as unknown as DriverCompletionPolicy
}
function parseDetail(v:unknown,assignment:string,id:string):DriverExceptionDetail {if(!exact(v,['exception','execution','actions']))malformed();const record=parseRecord(v.exception),execution=parseExecution(v.execution);if(record.id!==id||record.assignmentId!==assignment||!exact(v.actions,['mode','requestSupplement','resolve','uploadSupplement','submitSupplement','requirements'])||!member(v.actions.mode,['UNCONFIRMED','SYNTHETIC_TEST_ONLY']))malformed()
 const actions={...v.actions,mode:v.actions.mode,requestSupplement:parseAction(v.actions.requestSupplement),resolve:parseAction(v.actions.resolve),uploadSupplement:parseAction(v.actions.uploadSupplement),submitSupplement:parseAction(v.actions.submitSupplement),requirements:v.actions.requirements===null?null:parseRequirements(v.actions.requirements)}
 if(actions.requestSupplement.allowed||actions.requestSupplement.blocker!=='FORBIDDEN'||actions.resolve.allowed||actions.resolve.blocker!=='FORBIDDEN'||actions.mode==='UNCONFIRMED'&&(actions.uploadSupplement.allowed||actions.submitSupplement.allowed||actions.requirements!==null)||record.status!=='NEEDS_SUPPLEMENT'&&(actions.uploadSupplement.allowed||actions.submitSupplement.allowed||actions.requirements!==null)||(actions.uploadSupplement.allowed||actions.submitSupplement.allowed)&&actions.requirements===null||actions.requirements!==null&&(actions.mode!=='SYNTHETIC_TEST_ONLY'||!['DEPARTED','IN_PROGRESS','AWAITING_COMPLETION'].includes(execution.phase)))malformed()
 return {exception:record,execution,actions} as DriverExceptionDetail
}
function parseEvent(value: unknown, expected: string): DriverExceptionEvent {
  if (!exact(value, ['id', 'exceptionId', 'type', 'fromStatus', 'toStatus', 'fromVersion', 'toVersion', 'occurredAt', 'note', 'requestId', 'evidenceIds']) || !canonical(value.id) || value.exceptionId !== expected || !member(value.type, ['REPORTED', 'SUPPLEMENT_REQUESTED', 'SUPPLEMENT_EVIDENCE_UPLOADED', 'SUPPLEMENT_SUBMITTED', 'RESOLVED']) || !nullable(value.fromStatus, (entry): entry is DriverExceptionStatus => member(entry, statusValues)) || !member(value.toStatus, statusValues) || !nullable(value.fromVersion, (entry): entry is number => integer(entry)) || !integer(value.toVersion) || !timestamp(value.occurredAt) || !nullable(value.note, (entry): entry is string => fact(entry, value.type === 'REPORTED' ? 2000 : 1000)) || !nullable(value.requestId, canonical) || !identifiers(value.evidenceIds)) malformed()
  const type = value.type
  if (type === 'REPORTED') { if (value.fromStatus !== null || value.fromVersion !== null || value.toStatus !== 'OPEN' || value.toVersion !== 0 || value.note === null || value.requestId !== null || value.evidenceIds.length) malformed() }
  else {
    if (value.fromVersion === null || value.toVersion !== value.fromVersion + 1 || ['SUPPLEMENT_EVIDENCE_UPLOADED', 'SUPPLEMENT_SUBMITTED'].includes(type) && (value.fromVersion < 1 || value.toVersion < 2) || (type === 'RESOLVED' ? value.requestId !== null : value.requestId === null)) malformed()
    if (type === 'SUPPLEMENT_REQUESTED' ? value.fromStatus !== 'OPEN' || value.toStatus !== 'NEEDS_SUPPLEMENT' || value.note === null || value.evidenceIds.length > 0 : type === 'SUPPLEMENT_EVIDENCE_UPLOADED' ? value.fromStatus !== 'NEEDS_SUPPLEMENT' || value.toStatus !== 'NEEDS_SUPPLEMENT' || value.note !== null || value.evidenceIds.length !== 1 : type === 'SUPPLEMENT_SUBMITTED' ? value.fromStatus !== 'NEEDS_SUPPLEMENT' || value.toStatus !== 'OPEN' || value.note === null || value.evidenceIds.length === 0 : value.fromStatus !== 'OPEN' || value.toStatus !== 'RESOLVED' || value.note === null || value.evidenceIds.length > 0) malformed()
  }
  return value as unknown as DriverExceptionEvent
}
function page<T>(v:unknown,parse:(x:unknown)=>T):DriverPage<T>{if(!exact(v,['content','page','size','totalElements','totalPages'])||!Array.isArray(v.content)||!integer(v.page,0,100000)||!integer(v.size,1,100)||!integer(v.totalElements)||!integer(v.totalPages)||v.content.length>v.size)malformed();if(v.totalPages!==Number((BigInt(v.totalElements)+BigInt(v.size)-1n)/BigInt(v.size))||v.content.length!==Math.min(v.size,Math.max(0,v.totalElements-v.page*v.size)))malformed();return {...v,content:v.content.map(parse)} as unknown as DriverPage<T>}
function id(v:unknown):string {if(!uuid(v))input('請提供完整識別。');return v.toLowerCase()}
function params(query:DriverPageQuery,allowed:string[]):URLSearchParams {if(!object(query)||Object.keys(query).some(k=>!allowed.includes(k))||!integer(query.page??0,0,100000)||!integer(query.size??20,1,100))input('查詢或分頁不正確。');return new URLSearchParams({page:String(query.page??0),size:String(query.size??20)})}

type Family=17|18|19|20
const reasons:Record<number,Record<string,string>>={17:{E1701:'VERSION_CONFLICT',E1702:'INVALID_STATE',E1703:'POLICY_NOT_CONFIRMED',E1704:'IDEMPOTENCY_KEY_REUSED',E1705:'IDEMPOTENCY_IN_PROGRESS',E1706:'PROTECTED_RELATION'},18:{E1801:'VERSION_CONFLICT',E1802:'INVALID_STATE',E1803:'POLICY_NOT_CONFIRMED',E1804:'IDEMPOTENCY_KEY_REUSED',E1805:'IDEMPOTENCY_IN_PROGRESS',E1806:'PROTECTED_RELATION',E1807:'EVIDENCE_REQUIRED',E1808:'EVIDENCE_INVALID'},19:{E1901:'VERSION_CONFLICT',E1902:'INVALID_STATE',E1903:'POLICY_NOT_CONFIRMED',E1904:'IDEMPOTENCY_KEY_REUSED',E1905:'IDEMPOTENCY_IN_PROGRESS',E1906:'PROTECTED_RELATION',E1907:'SUPPLEMENT_REQUIRED',E1908:'EVIDENCE_INVALID'},20:{E2001:'VERSION_CONFLICT',E2002:'INVALID_STATE',E2003:'POLICY_NOT_CONFIRMED',E2004:'IDEMPOTENCY_KEY_REUSED',E2005:'IDEMPOTENCY_IN_PROGRESS',E2006:'PROTECTED_RELATION',E2007:'EVIDENCE_REQUIRED',E2008:'EVIDENCE_INVALID',E2009:'UNRESOLVED_EXCEPTIONS'}}
interface PrivatePng {blob:Blob;bytes:Uint8Array<ArrayBuffer>;sha256:string}
interface ScopeState {active:boolean;paused:boolean;intents:Set<DriverCommandIntent>;controllers:Map<AbortController,DriverCommandIntent>}
interface IntentState {scope:DriverCommandScope;task:DriverTaskBaseline;exception?:DriverExceptionRecord;requestId?:string;body:Record<string,unknown>;wire?:string;photo?:PrivatePng;key:string;path:string;family:Family;review:DriverCommandReview}
const scopes=new WeakMap<DriverCommandScope,ScopeState>(),intents=new WeakMap<DriverCommandIntent,IntentState>(),photos=new WeakMap<PreparedDriverPng,PrivatePng>()
const errors=new WeakMap<DriverApiError,{intent:DriverCommandIntent;dispatched:boolean;eligible:boolean}>()
function localError(code:string,message:string,uncertain=false) {return new DriverApiError(0,code,message,{uncertain})}
function deepFreeze<T>(value:T):T {if(value&&typeof value==='object'){Object.values(value).forEach(deepFreeze);Object.freeze(value)}return value}
export function createDriverCommandScope():DriverCommandScope {const scope=Object.freeze({}) as DriverCommandScope;scopes.set(scope,{active:true,paused:false,intents:new Set(),controllers:new Map()});return scope}
export function driverCommandScopeReady(scope:DriverCommandScope){const state=scopes.get(scope);return Boolean(state?.active&&!state.paused)}
export function pauseDriverCommandScope(scope:DriverCommandScope){const state=scopes.get(scope);if(!state?.active)return;state.paused=true;for(const controller of state.controllers.keys())controller.abort()}
export function resumeDriverCommandScope(scope:DriverCommandScope){const state=scopes.get(scope);if(state?.active)state.paused=false}
export function driverIntentRetryable(intent:DriverCommandIntent){const state=intents.get(intent);return Boolean(state&&scopes.get(state.scope)?.active)}
export function retireDriverIntent(intent:DriverCommandIntent):DriverUnresolvedIndicator {const state=intents.get(intent);if(state){const scope=scopes.get(state.scope);for(const [controller,owner] of scope?.controllers??[])if(owner===intent)controller.abort();scope?.intents.delete(intent);intents.delete(intent)}return Object.freeze({kind:intent.kind,retryable:false,outcome:'UNKNOWN'})}
export function retireDriverCommandScope(scope:DriverCommandScope){const state=scopes.get(scope);if(!state)return;state.active=false;state.paused=true;for(const controller of state.controllers.keys())controller.abort();for(const intent of [...state.intents])retireDriverIntent(intent);state.controllers.clear();scopes.delete(scope)}
function gate(intent:DriverCommandIntent,signal?:AbortSignal):IntentState {const state=intents.get(intent);if(!state||!scopes.get(state.scope)?.active)throw localError('INTENT_RETIRED','原次內容已清除，無法再以此識別重送。');if(signal?.aborted)throw localError('REQUEST_ABORTED','操作已中斷。');if(!driverCommandScopeReady(state.scope))throw localError('DISPATCH_PAUSED','目前暫停傳送，請恢復後明確重試。');return state}
const signature=[137,80,78,71,13,10,26,10]
async function digest(bytes:Uint8Array<ArrayBuffer>){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(n=>n.toString(16).padStart(2,'0')).join('')}
export async function prepareDriverPng(input:Blob|Uint8Array,signal?:AbortSignal):Promise<PreparedDriverPng>{
 const blob=input instanceof Uint8Array?new Blob([Uint8Array.from(input)],{type:'image/png'}):input instanceof Blob?input.slice(0,input.size,input.type):null
 if(!blob||blob.size<1||blob.size>1048576||blob.type!=='image/png')inputErrorPhoto()
 if(signal?.aborted)throw localError('REQUEST_ABORTED','照片讀取已中斷。')
 const bytes=new Uint8Array(await blob.arrayBuffer());if(signal?.aborted)throw localError('REQUEST_ABORTED','照片讀取已中斷。');if(!signature.every((n,i)=>bytes[i]===n))inputErrorPhoto()
 const sha256=await digest(bytes);if(signal?.aborted)throw localError('REQUEST_ABORTED','照片讀取已中斷。');const photo=Object.freeze({mediaType:'image/png',byteSize:bytes.length}) as PreparedDriverPng;photos.set(photo,{blob,bytes,sha256});return photo
}
function inputErrorPhoto():never {input('請選擇原始 PNG 照片，檔案須在技術傳輸上限內。')}
function canonicalIds(v:unknown):string[]{if(!Array.isArray(v)||v.length>32||!v.every(uuid))input('請明確選擇有效證據。');const result=v.map(x=>x.toLowerCase()).sort();if(new Set(result).size!==result.length)input('證據不可重複。');return result}
function taskBaseline(v:unknown):DriverTaskBaseline {if(!exact(v,['assignmentId','taskId','taskNo','execution'])||!canonical(v.assignmentId)||!canonical(v.taskId)||!text(v.taskNo,100,1))input('原任務識別無法確認，請重新讀取。');let execution:DriverExecutionView;try{execution=parseExecution(v.execution)}catch{input('原執行版本無法確認，請重新讀取。')}if(execution.phase==='COMPLETED')input('新操作必須使用尚未完成的原始資料。');return deepFreeze(structuredClone({...v,execution})) as unknown as DriverTaskBaseline}
export function prepareDriverCommand<K extends DriverCommandKind>(scope:DriverCommandScope,kind:K,submitted:DriverCommandInputMap[K],original:K extends 'supplementUpload'|'supplementSubmit'?DriverSupplementBaseline:DriverTaskBaseline):DriverCommandIntent {
 if(!driverCommandScopeReady(scope))throw localError('DISPATCH_PAUSED','目前無法建立新操作。')
 const supplemental=kind==='supplementUpload'||kind==='supplementSubmit'
 let task:DriverTaskBaseline,exception:DriverExceptionRecord|undefined,requestId:string|undefined
 if(supplemental){if(!exact(original,['task','exception','requestId']))input('補件原始資料不完整。');task=taskBaseline(original.task);try{exception=structuredClone(parseRecord(original.exception))}catch{input('補件原始資料無法確認。')}requestId=id(original.requestId);if(exception.assignmentId!==task.assignmentId||exception.taskId!==task.taskId||exception.status!=='NEEDS_SUPPLEMENT'||exception.activeRequest?.id!==requestId)input('補件要求不屬於原任務。')}
 else task=taskBaseline(original)
 const keys:Record<DriverCommandKind,string[]>={depart:['expectedVersion'],progress:['expectedVersion','targetPhase','evidenceIds'],upload:['expectedVersion','photo'],report:['expectedExecutionVersion','description'],supplementUpload:['expectedVersion','expectedExecutionVersion','photo'],supplementSubmit:['expectedVersion','note','evidenceIds'],complete:['expectedVersion','evidenceIds','confirmSafeRelease']}
 if(!exact(submitted,keys[kind]))input('請依原操作提供明確欄位。')
 const body:Record<string,unknown>={...submitted};let photo:PrivatePng|undefined
 if(kind==='upload'||kind==='supplementUpload'){photo=photos.get(body.photo as PreparedDriverPng);if(!photo)inputErrorPhoto();delete body.photo}
 if(Object.hasOwn(body,'expectedVersion')&&(!integer(body.expectedVersion)||body.expectedVersion!==(supplemental?exception!.version:task.execution.version)))input('原版本單位不一致。')
 if(Object.hasOwn(body,'expectedExecutionVersion')&&(!integer(body.expectedExecutionVersion)||body.expectedExecutionVersion!==task.execution.version))input('原執行版本不一致。')
 if(Object.hasOwn(body,'evidenceIds'))body.evidenceIds=canonicalIds(body.evidenceIds)
 if(kind==='report'&&!fact(body.description,2000)||kind==='supplementSubmit'&&!fact(body.note,1000))input('請填寫有效非空白原文。')
 const phase=task.execution.phase
 if(kind==='depart'&&phase!=='WAITING'||kind==='progress'&&(phase!=='DEPARTED'&&phase!=='IN_PROGRESS'||body.targetPhase!==(phase==='DEPARTED'?'IN_PROGRESS':'AWAITING_COMPLETION'))||kind==='upload'&&!['DEPARTED','IN_PROGRESS'].includes(phase)||['report','supplementUpload','supplementSubmit'].includes(kind)&&!['DEPARTED','IN_PROGRESS','AWAITING_COMPLETION'].includes(phase)||kind==='complete'&&(phase!=='AWAITING_COMPLETION'||body.confirmSafeRelease!==true))input('此原始階段不能建立指定操作。')
 const base=`/api/v1/tms/my-tasks/${task.assignmentId}`,suffix:Record<DriverCommandKind,string>={depart:'/depart',progress:'/progress',upload:'/evidence',report:'/exceptions',supplementUpload:`/exceptions/${exception?.id}/supplement-requests/${requestId}/evidence`,supplementSubmit:`/exceptions/${exception?.id}/supplement-requests/${requestId}/submit`,complete:'/complete'}
 const review:DriverCommandReview={kind,assignmentId:task.assignmentId,taskId:task.taskId,taskNo:task.taskNo,expectedExecutionVersion:task.execution.version,...(supplemental?{exceptionId:exception!.id,requestId,expectedExceptionVersion:exception!.version}:{}),...(typeof body.description==='string'?{description:body.description}:{}),...(typeof body.note==='string'?{note:body.note}:{}),...(Array.isArray(body.evidenceIds)?{evidenceIds:[...body.evidenceIds] as string[]}:{}),...(typeof body.targetPhase==='string'?{targetPhase:body.targetPhase}:{}),...(photo?{photoBytes:photo.bytes.length}:{}),...(kind==='complete'?{confirmSafeRelease:true as const}:{})}
 const intent=Object.freeze({kind}) as unknown as DriverCommandIntent,state:IntentState={scope,task,exception:exception?deepFreeze(exception):undefined,requestId,body:deepFreeze(body),wire:photo?undefined:JSON.stringify(body),photo,key:crypto.randomUUID(),path:base+suffix[kind],family:kind==='depart'?17:['report','supplementUpload','supplementSubmit'].includes(kind)?19:kind==='complete'?20:18,review:deepFreeze(review)}
 intents.set(intent,state);scopes.get(scope)!.intents.add(intent);return intent
}
export function driverCommandReview(intent:DriverCommandIntent):DriverCommandReview {const state=intents.get(intent);if(!state)throw localError('INTENT_RETIRED','原次內容已清除。');return state.review}
function parseError(v:unknown,status:number,family:Family,writing:boolean,kind?:DriverCommandKind):{error:DriverApiError;minimal:boolean}{
 const common:Record<string,number>={E0400:400,E0401:401,E0403:403,E0404:404,E0405:405,E0415:415,E0500:500,...(family===17?{}:{E0413:413})}
 if(!exact(v,['code','message','data','timestamp'])||typeof v.code!=='string'||!text(v.message,Infinity)||!timestamp(v.timestamp)||!v.timestamp.endsWith('+08:00')||((Object.hasOwn(reasons[family],v.code)?409:common[v.code])!==status))malformed()
 const data=v.data,allowed=family===19?['reason','actualVersion','actualExecutionVersion','fields']:['reason','actualVersion','fields']
 if(data!==null&&(!object(data)||!Object.keys(data).length||Object.keys(data).some(k=>!allowed.includes(k))||Object.hasOwn(data,'reason')&&!text(data.reason,Infinity, family===17?1:0)||Object.hasOwn(data,'actualVersion')&&!integer(data.actualVersion)||Object.hasOwn(data,'actualExecutionVersion')&&!integer(data.actualExecutionVersion)||Object.hasOwn(data,'fields')&&(!object(data.fields)||!Object.keys(data.fields).length||!Object.values(data.fields).every(x=>typeof x==='string'))))malformed()
 if([401,404,405,413,415,500].includes(status)&&data!==null||status===400&&(!object(data)||data.reason!=='VALIDATION_ERROR'||!object(data.fields)||!Object.keys(data.fields).length)||status===403&&data!==null&&(!exact(data,['reason'])||!member(data.reason,['CSRF_INVALID','FORBIDDEN'])))malformed()
 if(Object.hasOwn(reasons[family],v.code)&&(!object(data)||data.reason!==reasons[family][v.code]))malformed()
 let unit='actualVersion'
 if(v.code===`E${family}01`){if(!object(data))malformed();if(family===19){if(Object.hasOwn(data,'actualVersion')===Object.hasOwn(data,'actualExecutionVersion'))malformed();unit=Object.hasOwn(data,'actualExecutionVersion')?'actualExecutionVersion':'actualVersion';if(writing&&(kind==='report'&&unit!=='actualExecutionVersion'||kind==='supplementSubmit'&&unit!=='actualVersion'))malformed()}else if(!Object.hasOwn(data,'actualVersion'))malformed()}
 const minimal=object(data)&&exact(data,v.code===`E${family}01`?['reason',unit]:['reason'])
 return {error:new DriverApiError(status,v.code,status===401?'登入狀態已失效，請重新登入。':status>=500?'服務暫時無法確認，請保留原次結果。':v.message,{...(data as object??{}),uncertain:writing&&(status>=500||[`E${family}04`,`E${family}05`,`E${family}06`].includes(v.code))}),minimal}
}
async function boundedBytes(response:Response):Promise<Uint8Array<ArrayBuffer>> {if(!response.body){const bytes=new Uint8Array(await response.arrayBuffer());if(bytes.length>1048576)malformed();return bytes}const reader=response.body.getReader(),parts:Uint8Array[]=[];let length=0;try{for(;;){const next=await reader.read();if(next.done)break;length+=next.value.length;if(length>1048576){await reader.cancel();malformed()}parts.push(next.value)}}finally{reader.releaseLock()}const bytes=new Uint8Array(length);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length}return bytes}
function protocol(status:number,writing:boolean){return new DriverApiError(status,'INVALID_RESPONSE','服務回應無法確認，請保留原次操作。',{uncertain:writing})}
async function transport<T>(path:string,family:Family,parse:(v:unknown)=>T,signal?:AbortSignal,intent?:DriverCommandIntent,photo?:DriverEvidence):Promise<{data:T;replayed:boolean}>{
 let dispatched=false,minimal=false;const writing=intent!==undefined,controller=new AbortController(),abort=()=>controller.abort();let state:IntentState|undefined,scope:ScopeState|undefined,timer:ReturnType<typeof setTimeout>|undefined
 signal?.addEventListener('abort',abort,{once:true})
 try{
  if(signal?.aborted)throw localError('REQUEST_ABORTED','操作已中斷。')
  if(intent){state=gate(intent,controller.signal);scope=scopes.get(state.scope)!;scope.controllers.set(controller,intent);if(state.photo){const hash=await digest(state.photo.bytes);gate(intent,controller.signal);if(hash!==state.photo.sha256)throw protocol(0,true)} }
  let csrf:Record<string,string>={}
  if(intent){try{csrf=await getCsrfHeaders(controller.signal)}catch(error){if(error instanceof ApiError&&[401,403].includes(error.status))throw new DriverApiError(error.status,error.status===401?'E0401':'E0403','登入或存取狀態已失效。',{reason:error.status===403?'FORBIDDEN':null});throw localError(controller.signal.aborted?'REQUEST_ABORTED':'NETWORK_ERROR','尚未傳送本次操作。')}state=gate(intent,controller.signal)}
  if(controller.signal.aborted)throw localError('REQUEST_ABORTED','操作已中斷。')
  timer=setTimeout(abort,10000)
  dispatched=writing
  const response=await fetch(path,{method:writing?'POST':'GET',credentials:'same-origin',redirect:'error',cache:'no-store',signal:controller.signal,headers:{Accept:photo?'image/png':'application/json',...(state?{'Content-Type':state.photo?'image/png':'application/json',...csrf,'Idempotency-Key':state.key,...(state.photo?{'X-Execution-Version':String(state.task.execution.version),...(intent!.kind==='supplementUpload'?{'X-Exception-Version':String(state.exception!.version)}:{})}:{})}:{})},...(state?{body:state.photo?state.photo.blob:state.wire}:{})})
  let bytes:Uint8Array<ArrayBuffer>;try{bytes=await boundedBytes(response)}catch{throw protocol(response.status,writing)}if(controller.signal.aborted)throw localError('REQUEST_ABORTED','操作已中斷。',writing)
  if(photo&&response.status===200){if(response.redirected||response.headers.get('Content-Type')!=='image/png'||response.headers.get('Content-Length')!==String(bytes.length)||response.headers.get('Cache-Control')!=='no-store, private'||response.headers.get('X-Content-Type-Options')!=='nosniff'||response.headers.get('Content-Disposition')!=='inline; filename="evidence.png"'||['ETag','Last-Modified','Accept-Ranges','Location','Content-Location'].some(n=>response.headers.has(n))||bytes.length!==photo.byteSize||!signature.every((n,i)=>bytes[i]===n)||await digest(bytes)!==photo.sha256||controller.signal.aborted)throw protocol(response.status,false);return {data:new Blob([bytes],{type:'image/png'}) as T,replayed:false}}
  if(response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase()!=='application/json')throw protocol(response.status,writing)
  let value:unknown;try{value=readTomJson(new TextDecoder('utf-8',{fatal:true}).decode(bytes))}catch{throw protocol(response.status,writing)}
  if(!response.ok){let error:DriverApiError;try{const parsed=parseError(value,response.status,family,writing,intent?.kind);error=parsed.error;minimal=parsed.minimal}catch{throw protocol(response.status,writing)}
   if(error.status===403&&error.reason==='CSRF_INVALID'){try{await refreshCsrf(controller.signal)}catch(refresh){if(refresh instanceof ApiError&&[401,403].includes(refresh.status))throw new DriverApiError(refresh.status,refresh.status===401?'E0401':'E0403','登入或存取狀態已失效。',{reason:refresh.status===403?'FORBIDDEN':null,uncertain:writing})}}
   throw error
  }
  try{if(photo||response.status!==200||response.redirected||!exact(value,['code','message','data','timestamp'])||value.code!=='0000'||typeof value.message!=='string'||!timestamp(value.timestamp)||!value.timestamp.endsWith('+08:00')||response.headers.has('Location'))malformed();const data=parse(value.data),header=response.headers.get('Idempotency-Replayed');if(writing&&header!=='true'&&header!=='false')malformed();return {data,replayed:header==='true'}}catch{throw protocol(response.status,writing)}
 }catch(cause){const error=cause instanceof DriverApiError?cause:localError(controller.signal.aborted?'REQUEST_ABORTED':'NETWORK_ERROR','無法確認服務回應，請保留原次操作。',writing);if(intent){errors.set(error,{intent,dispatched,eligible:dispatched&&minimal&&error.status===409&&!error.uncertain&&whitelist[intent.kind].includes(error.code)&&reasons[family][error.code]===error.reason})}throw error}
 finally{if(timer)clearTimeout(timer);signal?.removeEventListener('abort',abort);scope?.controllers.delete(controller)}
}
function bindRecord(record:DriverExceptionRecord,state:IntentState){if(record.assignmentId!==state.task.assignmentId||record.taskId!==state.task.taskId||record.taskNo!==state.task.taskNo)malformed()}
function commandResult(v:unknown,intent:DriverCommandIntent,state:IntentState):DriverCommandReceipt['data']{
 const {kind}=intent,{task}=state
 if(kind==='upload') {const result=parseEvidence(v,task.assignmentId,task.taskId);photoWitness(result,state);return result}
 if(kind==='report'||kind==='supplementSubmit') {const result=parseRecord(v);bindRecord(result,state)
  if(kind==='report'){if(result.version!==0||result.status!=='OPEN'||result.description!==state.body.description||result.reportedPhase!==task.execution.phase||result.reportedExecutionVersion!==task.execution.version||result.activeRequest!==null)malformed()}
  else {const before=state.exception!;for(const field of ['id','assignmentId','taskId','taskNo','reportedPhase','reportedExecutionVersion','description','reportedAt'] as const)if(result[field]!==before[field])malformed();if(result.version!==before.version+1||result.status!=='OPEN'||result.activeRequest!==null)malformed()}
  return result
 }
 if(kind==='supplementUpload'){if(!exact(v,['exceptionId','assignmentId','taskId','version','requestId','evidence'])||v.exceptionId!==state.exception!.id||v.requestId!==state.requestId||v.version!==state.exception!.version+1||!integer(v.version,2))malformed();scopeIds(v,task.assignmentId,task.taskId);const evidence=parseEvidence(v.evidence,task.assignmentId,task.taskId);photoWitness(evidence,state);if(state.exception!.activeRequest!.evidenceIds.includes(evidence.id))malformed();return {...v,evidence} as unknown as DriverSupplementUploadResult}
 if(!exact(v,kind==='complete'?['assignmentId','taskId','execution','completion']:['assignmentId','taskId','execution']))malformed();if(kind==='depart'){if(!uuid(v.assignmentId)||!uuid(v.taskId)||v.assignmentId.toLowerCase()!==task.assignmentId||v.taskId.toLowerCase()!==task.taskId)malformed()}else scopeIds(v,task.assignmentId,task.taskId);const execution=parseExecution(v.execution)
 if(execution.version!==task.execution.version+1||!integer(execution.version)||execution.phase!==(kind==='depart'?'DEPARTED':kind==='complete'?'COMPLETED':state.body.targetPhase)||(kind==='depart'?execution.departedAt===null:execution.departedAt!==task.execution.departedAt))malformed()
 if(kind==='complete'){if(execution.version<4)malformed();return {...v,execution,completion:parseCompletion(v.completion)} as unknown as DriverCompleteResult}
 return {...v,execution} as unknown as DriverExecutionResult
}
function photoWitness(photo:DriverEvidence,state:IntentState){if(photo.phase!==state.task.execution.phase||photo.executionVersion!==state.task.execution.version||photo.mediaType!=='image/png'||photo.byteSize!==state.photo!.bytes.length||photo.sha256!==state.photo!.sha256)malformed()}
export async function sendDriverCommand(intent:DriverCommandIntent,signal?:AbortSignal):Promise<DriverCommandReceipt>{const state=gate(intent,signal);const result=await transport(state.path,state.family,v=>commandResult(v,intent,state),signal,intent);return {kind:intent.kind,data:result.data,replayed:result.replayed} as DriverCommandReceipt}
const whitelist:Record<DriverCommandKind,string[]>={depart:['E1701','E1702','E1703'],upload:['E1801','E1802','E1803'],progress:['E1801','E1802','E1803','E1807','E1808'],report:['E1901','E1902','E1903'],supplementUpload:['E1901','E1902','E1903'],supplementSubmit:['E1901','E1902','E1903','E1907','E1908'],complete:['E2001','E2002','E2003','E2007','E2008','E2009']}
export function driverAttemptWasDispatched(error:unknown,intent:DriverCommandIntent):boolean|undefined {const source=error instanceof DriverApiError?errors.get(error):undefined;return source?.intent===intent?source.dispatched:undefined}
export function settlesDriverAttempt(error:unknown,intent:DriverCommandIntent):boolean {const source=error instanceof DriverApiError?errors.get(error):undefined;return Boolean(source&&source.intent===intent&&source.dispatched&&source.eligible&&driverIntentRetryable(intent))}
const base=(assignment:string)=>`/api/v1/tms/my-tasks/${assignment}`
export async function getDriverTask(assignmentId:string,signal?:AbortSignal):Promise<DriverTaskDetail>{const target=id(assignmentId);return (await transport(base(target)+'/completion',20,v=>parseTask(v,target),signal)).data}
export async function getDriverProgressPolicy(assignmentId:string,signal?:AbortSignal):Promise<DriverProgressPolicy>{const target=id(assignmentId);return (await transport(base(target)+'/progress-policy',18,v=>parseProgressPolicy(v,target),signal)).data}
export async function getDriverCompletionPolicy(assignmentId:string,signal?:AbortSignal):Promise<DriverCompletionPolicy>{const target=id(assignmentId);return (await transport(base(target)+'/completion-policy',20,v=>parseCompletionPolicy(v,target),signal)).data}
export async function getDriverEvidence(assignmentId:string,query:DriverPageQuery={},signal?:AbortSignal):Promise<DriverPage<DriverEvidence>>{const target=id(assignmentId);return (await transport(`${base(target)}/evidence?${params(query,['page','size'])}`,18,v=>page(v,x=>parseEvidence(x,target)),signal)).data}
export async function getDriverEvidenceContent(assignmentId:string,submitted:DriverEvidence,signal?:AbortSignal):Promise<Blob>{const target=id(assignmentId);let photo:DriverEvidence;try{photo=structuredClone(parseEvidence(submitted,target))}catch{input('照片來源無法確認。')}return (await transport<Blob>(`${base(target)}/evidence/${photo.id}/content`,18,()=>malformed(),signal,undefined,photo)).data}
export async function getDriverExceptions(assignmentId:string,query:DriverExceptionQuery={},signal?:AbortSignal):Promise<DriverPage<DriverExceptionSummary>>{const target=id(assignmentId),search=params(query,['page','size','status']);if(query.status!==undefined){if(!member(query.status,statusValues))input('異常狀態不正確。');search.set('status',query.status)}return (await transport(`${base(target)}/exceptions?${search}`,19,v=>page(v,x=>{const record=parseSummary(x);if(record.assignmentId!==target)malformed();return record}),signal)).data}
export async function getDriverException(assignmentId:string,exceptionId:string,signal?:AbortSignal):Promise<DriverExceptionDetail>{const target=id(assignmentId),exception=id(exceptionId);return (await transport(`${base(target)}/exceptions/${exception}`,19,v=>parseDetail(v,target,exception),signal)).data}
export async function getDriverExceptionHistory(assignmentId:string,exceptionId:string,query:DriverPageQuery={},signal?:AbortSignal):Promise<DriverPage<DriverExceptionEvent>>{const target=id(assignmentId),exception=id(exceptionId);return (await transport(`${base(target)}/exceptions/${exception}/history?${params(query,['page','size'])}`,19,v=>page(v,x=>parseEvent(x,exception)),signal)).data}
