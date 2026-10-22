/** API contracts mirror public Java DTOs; persistence and tenant internals never enter the UI. */
export type Status =
  | "CONFIRMED"
  | "PREPARED"
  | "DISPATCHED"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "CANCELLED";
export interface Warehouse {
  id: string;
  name: string;
  city: string;
  countryCode: string;
}
export interface Product {
  id: string;
  name: string;
  sku: string;
  weightKg: number;
}
export interface Stock {
  id: string;
  productId: string;
  onHand: number;
  reserved: number;
  available: number;
}
export interface Order {
  id: string;
  warehouseId: string;
  customerReference: string;
  status: Status;
  createdAt: string;
  items: {
    productId: string;
    productName: string;
    sku: string;
    quantity: number;
  }[];
}
export interface Shipment {
  id: string;
  orderId: string;
  warehouseId: string;
  trackingNumber: string;
  carrier: string;
  status: Status;
  createdAt: string;
}
export interface HistoryEvent extends AuditActor {
  id: string;
  sequence: number;
  status: Status;
  note: string;
  createdAt: string;
}
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Exact public order detail fields, distinct from the smaller table view. */
export interface OrderDetail extends Order {
  requestId: string;
  reservationId: string;
  dispatchedAt: string | null;
  deliveredAt: string | null;
  cancelledAt: string | null;
}
export interface StockMovement extends AuditActor {
  id: string;
  type: "OPENING" | "RECEIPT" | "ADJUSTMENT" | "DISPATCH";
  quantity: number;
  onHandBefore: number;
  onHandAfter: number;
  reason: string;
  shipmentId: string | null;
  createdAt: string;
}

/** Immutable OIDC identity. Null means historical or non-user work without recorded attribution. */
export interface AuditActor {
  actorSubject?: string | null;
  actorIssuer?: string | null;
  actorName?: string | null;
}
