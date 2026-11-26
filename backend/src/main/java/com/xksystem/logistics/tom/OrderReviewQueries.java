package com.xksystem.logistics.tom;
import java.util.List;
import java.util.UUID;
/** Trusted TOM-owned source view; current metadata is not an approval or HTTP grant. */
public interface OrderReviewQueries {
 record Source(UUID orderId,String orderNo,String orderType,String lifecycleStatus,String acceptanceStatus,long version,List<TaskQueries.Source> tasks,boolean acceptedSourceIntact){}
 Source find(UUID orderId);
 /** Caller canonical manager/session first; joins writable transaction and locks both TOM roots. */
 Source lockForReview(UUID orderId);
}
