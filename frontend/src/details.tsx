import { Badge, date, Empty, label } from "./components";
import { useResource } from "./useResource";
import type { OrderDetail, StockMovement, Warehouse } from "./types";

/** Reusable read failure: keep the dialog open so the user can recover without losing context. */
export function ReadError({
  message,
  retry,
}: {
  message: string;
  retry: () => void;
}) {
  return (
    <div className="read-error">
      <p className="form-error" role="alert">
        {message}
      </p>
      <button type="button" onClick={retry}>
        Try again
      </button>
    </div>
  );
}

/** Always fetch the full order instead of treating a possibly stale list row as current state. */
export function OrderDetails({
  id,
  warehouses,
}: {
  id: string;
  warehouses: Warehouse[];
}) {
  const {
    data: order,
    loading,
    error,
    reload,
  } = useResource<OrderDetail>(`/orders/${id}`);
  if (loading) return <p role="status">Loading order details…</p>;
  if (error) return <ReadError message={error} retry={reload} />;
  if (!order) return null;
  return (
    <div className="order-details">
      <div className="detail-heading">
        <div>
          <strong>{order.customerReference}</strong>
          <p className="help">
            {warehouses.find((warehouse) => warehouse.id === order.warehouseId)
              ?.name || order.warehouseId}
          </p>
        </div>
        <Badge status={order.status} />
      </div>
      <dl className="detail-grid">
        <div>
          <dt>Order ID</dt>
          <dd>{order.id}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{date(order.createdAt)}</dd>
        </div>
        <div>
          <dt>Reservation ID</dt>
          <dd>{order.reservationId}</dd>
        </div>
        <div>
          <dt>Request ID</dt>
          <dd>{order.requestId}</dd>
        </div>
        {order.dispatchedAt && (
          <div>
            <dt>Dispatched</dt>
            <dd>{date(order.dispatchedAt)}</dd>
          </div>
        )}
        {order.deliveredAt && (
          <div>
            <dt>Delivered</dt>
            <dd>{date(order.deliveredAt)}</dd>
          </div>
        )}
        {order.cancelledAt && (
          <div>
            <dt>Cancelled</dt>
            <dd>{date(order.cancelledAt)}</dd>
          </div>
        )}
      </dl>
      <h3>Ordered products</h3>
      <p className="help">
        Original catalog snapshots, preserved even if a product is renamed
        later.
      </p>
      <div className="table-scroll detail-table">
        <table>
          <thead>
            <tr>
              <th>Product</th>
              <th>SKU</th>
              <th>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.productId}>
                <td>
                  <strong>{item.productName}</strong>
                </td>
                <td>{item.sku}</td>
                <td>{item.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="detail-total">
        <span>Total ordered units</span>
        <strong>
          {order.items.reduce((sum, item) => sum + item.quantity, 0)}
        </strong>
      </div>
    </div>
  );
}

/** Ledger snapshots explain physical changes; holds do not create physical movements. */
export function MovementHistory({
  warehouseId,
  productId,
}: {
  warehouseId: string;
  productId: string;
}) {
  const {
    data: movements,
    loading,
    error,
    reload,
  } = useResource<StockMovement[]>(
    `/warehouses/${warehouseId}/stock/${productId}/movements`,
  );
  if (loading) return <p role="status">Loading movement history…</p>;
  if (error) return <ReadError message={error} retry={reload} />;
  if (!movements?.length)
    return (
      <Empty title="No movement history">
        No physical stock changes have been recorded for this product.
      </Empty>
    );
  return (
    <>
      <p className="help">
        Physical stock changes, oldest first. Reservations affect availability,
        not this ledger.
      </p>
      <ol className="movement-list">
        {movements.map((movement) => (
          <li key={movement.id}>
            <div className="movement-title">
              <strong>{label(movement.type)}</strong>
              <span
                className={
                  movement.quantity < 0
                    ? "quantity negative"
                    : "quantity positive"
                }
              >
                {movement.quantity > 0 ? "+" : ""}
                {movement.quantity} units
              </span>
            </div>
            <p>{movement.reason}</p>
            <div className="movement-meta">
              <span>
                {movement.onHandBefore} → {movement.onHandAfter} on hand
              </span>
              <time dateTime={movement.createdAt}>
                {date(movement.createdAt)}
              </time>
            </div>
            {movement.shipmentId && (
              <small className="movement-shipment">
                Shipment {movement.shipmentId}
              </small>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}
