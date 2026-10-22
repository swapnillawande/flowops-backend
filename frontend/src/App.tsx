import { useEffect, useState } from "react";
import {
  ArrowDownLeft,
  ArrowUpRight,
  Boxes,
  Check,
  ChevronRight,
  CircleHelp,
  LayoutDashboard,
  LogOut,
  Package,
  Plus,
  RefreshCw,
  Route,
  Truck,
  Warehouse as WarehouseIcon,
  X,
} from "lucide-react";
import { api, post } from "./api";
import {
  Badge,
  date,
  Empty,
  History,
  Modal,
  MutationForm,
  Pager,
  short,
} from "./components";
import {
  CatalogForms,
  OpeningForm,
  StockMovementForm,
  OrderForm,
  PrepareForm,
  StatusForm,
} from "./forms";
import type { Order, Page, Product, Shipment, Stock, Warehouse } from "./types";

import type { WorkspaceSession } from "./auth";

import { OrderDetails, MovementHistory } from "./details";

type Tab = "overview" | "orders" | "shipments" | "catalog";
type Dialog =
  | { kind: "order" }
  | { kind: "order-details"; id: string }
  | { kind: "prepare"; order: Order }
  | { kind: "history"; shipment: Shipment }
  | { kind: "status"; shipment: Shipment }
  | {
      kind: "confirm";
      title: string;
      description: string;
      run: () => Promise<unknown>;
    };
const emptyPage = <T,>(): Page<T> => ({
  content: [],
  page: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
});
const navigation = [
  { id: "overview", title: "Overview", icon: LayoutDashboard },
  { id: "orders", title: "Orders", icon: Package },
  { id: "shipments", title: "Shipments", icon: Truck },
  { id: "catalog", title: "Catalog & stock", icon: Boxes },
] as const;
const titles = {
  overview: [
    "Your operations, at a glance.",
    "A clear view of what’s moving and what needs your attention.",
  ],
  orders: ["Orders", "From customer request to reserved stock."],
  shipments: [
    "Shipments",
    "Keep every delivery moving in the right direction.",
  ],
  catalog: [
    "Catalog & stock",
    "Set up your warehouses, products, and opening inventory.",
  ],
};

/** Fetch only real API data. Abort superseded reads so an old page cannot overwrite a newer one. */
export default function App({
  session,
  onSignOut,
}: {
  session?: WorkspaceSession;
  onSignOut?: () => Promise<void>;
}) {
  // This controls presentation; the API independently enforces every read/write permission.
  const canWrite = session ? session.roles.includes("operator") : true;
  const [tab, setTab] = useState<Tab>("overview"),
    [dialog, setDialog] = useState<Dialog | null>(null);
  const [orders, setOrders] = useState(emptyPage<Order>),
    [shipments, setShipments] = useState(emptyPage<Shipment>);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]),
    [products, setProducts] = useState<Product[]>([]);
  const [orderPage, setOrderPage] = useState(0),
    [shipmentPage, setShipmentPage] = useState(0),
    [revision, refresh] = useState(0);
  const [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    const options = { signal: controller.signal };
    Promise.all([
      api<Page<Order>>(`/orders?page=${orderPage}&size=10`, options),
      api<Page<Shipment>>(`/shipments?page=${shipmentPage}&size=10`, options),
      api<Warehouse[]>("/warehouses", options),
      api<Product[]>("/products", options),
    ])
      .then(([orders, shipments, warehouses, products]) => {
        if (!controller.signal.aborted) {
          setOrders(orders);
          setShipments(shipments);
          setWarehouses(warehouses);
          setProducts(products);
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [orderPage, shipmentPage, revision]);
  function done() {
    setDialog(null);
    setMessage("Update saved successfully.");
    refresh((value) => value + 1);
  }
  const warehouseName = (id: string) =>
    warehouses.find((warehouse) => warehouse.id === id)?.name || short(id);
  function confirmDispatch(shipment: Shipment) {
    setDialog({
      kind: "confirm",
      title: "Dispatch shipment",
      description:
        "All held units for this order will leave physical stock. After dispatch, this order can no longer be cancelled.",
      run: () => post(`/shipments/${shipment.id}/dispatch`),
    });
  }
  const orderTable = (rows: Order[]) =>
    !rows.length ? (
      <Empty title="No orders yet">
        Add products and stock in Catalog, then create your first order.
      </Empty>
    ) : (
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Order / customer</th>
              <th>Warehouse</th>
              <th>Units</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((order) => (
              <tr key={order.id}>
                <td>
                  <button
                    className="order-link"
                    onClick={() =>
                      setDialog({ kind: "order-details", id: order.id })
                    }
                  >
                    #{short(order.id)}
                    <ArrowUpRight size={13} />
                  </button>
                  <small>{order.customerReference}</small>
                </td>
                <td>{warehouseName(order.warehouseId)}</td>
                <td>
                  {order.items.reduce(
                    (total, item) => total + item.quantity,
                    0,
                  )}
                </td>
                <td>
                  <Badge status={order.status} />
                </td>
                <td>
                  <div className="row-actions">
                    {canWrite && order.status === "CONFIRMED" ? (
                      <>
                        <button
                          onClick={() => setDialog({ kind: "prepare", order })}
                        >
                          Prepare shipment
                          <ChevronRight size={14} />
                        </button>
                        <button
                          className="danger-link"
                          onClick={() =>
                            setDialog({
                              kind: "confirm",
                              title: "Cancel order",
                              description: `Cancel order #${short(order.id)} and release its stock hold? Any prepared shipment will also be cancelled.`,
                              run: () => post(`/orders/${order.id}/cancel`),
                            })
                          }
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <span className="muted">
                        {order.status === "CONFIRMED"
                          ? "Read-only access"
                          : order.status === "CANCELLED"
                            ? "Stock released"
                            : order.status === "DELIVERED"
                              ? "Completed"
                              : "Stock dispatched"}
                      </span>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  const shipmentTable = (rows: Shipment[]) =>
    !rows.length ? (
      <Empty title="Nothing on the road yet">
        Prepare a shipment from a confirmed order to get started.
      </Empty>
    ) : (
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Tracking / order</th>
              <th>Carrier</th>
              <th>Status</th>
              <th>Next step</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((shipment) => (
              <tr key={shipment.id}>
                <td>
                  <button
                    className="tracking-link"
                    onClick={() => setDialog({ kind: "history", shipment })}
                  >
                    {shipment.trackingNumber}
                    <ArrowUpRight size={13} />
                  </button>
                  <small>Order #{short(shipment.orderId)}</small>
                </td>
                <td>{shipment.carrier}</td>
                <td>
                  <Badge status={shipment.status} />
                </td>
                <td>
                  {!canWrite ? (
                    <button
                      onClick={() => setDialog({ kind: "history", shipment })}
                    >
                      View history
                    </button>
                  ) : shipment.status === "PREPARED" ? (
                    <button
                      className="action-button"
                      onClick={() => confirmDispatch(shipment)}
                    >
                      Dispatch
                      <ArrowUpRight size={14} />
                    </button>
                  ) : ["DISPATCHED", "IN_TRANSIT"].includes(shipment.status) ? (
                    <button
                      className="action-button"
                      onClick={() => setDialog({ kind: "status", shipment })}
                    >
                      {shipment.status === "DISPATCHED"
                        ? "Mark in transit"
                        : "Mark delivered"}
                      <ChevronRight size={14} />
                    </button>
                  ) : (
                    <button
                      onClick={() => setDialog({ kind: "history", shipment })}
                    >
                      View history
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setTab("overview");
          }}
        >
          <span className="brand-icon">
            <Route size={23} />
          </span>
          <span>
            flowops<span className="brand-period">.</span>
          </span>
        </a>
        <div className="workspace">
          <span className="workspace-avatar">FO</span>
          <div>
            <strong>{session?.username || "Operations workspace"}</strong>
            <small>Your secure workspace</small>
          </div>
        </div>
        <div className="nav-caption">WORKSPACE</div>
        <nav aria-label="Main navigation">
          {navigation.map(({ id, title, icon: Icon }) => (
            <button
              key={id}
              className={tab === id ? "selected" : ""}
              aria-current={tab === id ? "page" : undefined}
              onClick={() => setTab(id)}
            >
              <Icon size={18} />
              {title}
              {tab === id && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="tip">
            <CircleHelp size={18} />
            <strong>Built around your workflow</strong>
            <p>Catalog → order → shipment → delivery. One step at a time.</p>
          </div>
          <div className="local-label">
            <span />
            Development workspace
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace
            <ChevronRight size={13} />
            <strong>{navigation.find((item) => item.id === tab)?.title}</strong>
          </div>
          <span className="today">
            {new Date().toLocaleDateString(undefined, {
              month: "short",
              day: "numeric",
              year: "numeric",
            })}
          </span>
          {session && (
            <button className="sign-out" onClick={() => void onSignOut?.()}>
              <LogOut size={15} />
              Sign out
            </button>
          )}
          <span className="profile" aria-label={session?.username}>
            {session?.username.slice(0, 2).toUpperCase() || "FO"}
          </span>
        </header>
        <main>
          <div className="page-header">
            <div>
              <div className="eyebrow">FLOWOPS CONTROL TOWER</div>
              <h1>{titles[tab][0]}</h1>
              <p>
                {tab === "catalog" && !canWrite
                  ? "Browse warehouse stock and its recorded history."
                  : titles[tab][1]}
              </p>
            </div>
            <div className="header-actions">
              <button
                className="refresh"
                aria-label="Refresh data"
                disabled={loading}
                onClick={() => refresh((value) => value + 1)}
              >
                <RefreshCw size={17} className={loading ? "spin" : ""} />
              </button>
              {canWrite && (
                <button
                  className="primary"
                  disabled={loading || !!error}
                  onClick={() => setDialog({ kind: "order" })}
                >
                  <Plus size={17} />
                  New order
                </button>
              )}
            </div>
          </div>
          {!canWrite && (
            <div className="notice" role="status">
              Read-only access · You can browse records and history. Ask an
              operator to make changes.
            </div>
          )}
          {error && (
            <div className="notice error" role="alert">
              <strong>Data could not be refreshed.</strong> {error}{" "}
              <button onClick={() => refresh((value) => value + 1)}>
                Try again
              </button>
            </div>
          )}
          {message && (
            <div className="notice success" role="status">
              <Check size={17} />
              {message}
              <button
                aria-label="Dismiss notification"
                onClick={() => setMessage("")}
              >
                <X size={15} />
              </button>
            </div>
          )}
          {tab === "overview" && (
            <>
              <div className="stats">
                {[
                  {
                    title: "Total orders",
                    value: orders.totalElements,
                    icon: Package,
                    text: "Across your workspace",
                  },
                  {
                    title: "Total shipments",
                    value: shipments.totalElements,
                    icon: Truck,
                    text: "Across all shipment statuses",
                  },
                  {
                    title: "Warehouses",
                    value: warehouses.length,
                    icon: WarehouseIcon,
                    text: "Inventory locations",
                  },
                  {
                    title: "Products",
                    value: products.length,
                    icon: Boxes,
                    text: "In your catalog",
                  },
                ].map(({ title, value, icon: Icon, text }) => (
                  <section className="stat" key={title}>
                    <div>
                      <span>{title}</span>
                      <Icon size={18} />
                    </div>
                    <strong>{loading || error ? "—" : value}</strong>
                    <small>{text}</small>
                  </section>
                ))}
              </div>
              <div className="workflow-strip">
                <span className="workflow-icon">
                  <Route size={21} />
                </span>
                <div>
                  <strong>A clear path from order to delivery</strong>
                  <p>
                    Reserve stock. Prepare a shipment. Dispatch. Track delivery.
                  </p>
                </div>
                <button onClick={() => setTab("shipments")}>
                  View shipments
                  <ArrowUpRight size={16} />
                </button>
              </div>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Orders in view</h2>
                    <p>Page {orderPage + 1} · up to 10 records</p>
                  </div>
                  <button onClick={() => setTab("orders")}>
                    All orders
                    <ChevronRight size={15} />
                  </button>
                </div>
                {loading ? (
                  <div className="loading" role="status">
                    Loading operations…
                  </div>
                ) : (
                  orderTable(orders.content)
                )}
              </section>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Shipment activity</h2>
                    <p>
                      Current shipment page · select a tracking number for its
                      history
                    </p>
                  </div>
                  <button onClick={() => setTab("shipments")}>
                    All shipments
                    <ChevronRight size={15} />
                  </button>
                </div>
                {!loading && shipmentTable(shipments.content)}
              </section>
            </>
          )}
          {tab === "orders" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>
                    All orders{" "}
                    <span className="count">{orders.totalElements}</span>
                  </h2>
                  <p>Stock holds stay linked to each order.</p>
                </div>
              </div>
              {loading ? (
                <div className="loading" role="status">
                  Loading orders…
                </div>
              ) : (
                orderTable(orders.content)
              )}
              <Pager
                page={orderPage}
                pages={orders.totalPages}
                total={orders.totalElements}
                onChange={setOrderPage}
              />
            </section>
          )}
          {tab === "shipments" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>
                    All shipments{" "}
                    <span className="count">{shipments.totalElements}</span>
                  </h2>
                  <p>Manual updates · no external carrier connection</p>
                </div>
              </div>
              {loading ? (
                <div className="loading" role="status">
                  Loading shipments…
                </div>
              ) : (
                shipmentTable(shipments.content)
              )}
              <Pager
                page={shipmentPage}
                pages={shipments.totalPages}
                total={shipments.totalElements}
                onChange={setShipmentPage}
              />
            </section>
          )}
          {tab === "catalog" && (
            <>
              {canWrite && <CatalogForms done={done} />}
              <Inventory
                canWrite={canWrite}
                warehouses={warehouses}
                products={products}
                revision={revision}
                done={done}
              />
            </>
          )}
          <footer>
            <span>
              <i className={error ? "offline" : ""} />
              {loading
                ? "Connecting to backend…"
                : error
                  ? "Backend unavailable · displayed data may be stale"
                  : "Connected to backend"}
            </span>
            <span>Secure team workspace</span>
          </footer>
        </main>
      </div>
      {dialog && (
        <Modal
          close={() => setDialog(null)}
          title={
            dialog.kind === "order-details"
              ? "Order details"
              : dialog.kind === "order"
                ? "Create order"
                : dialog.kind === "prepare"
                  ? "Prepare shipment"
                  : dialog.kind === "history"
                    ? "Shipment timeline"
                    : dialog.kind === "status"
                      ? "Update delivery status"
                      : dialog.title
          }
        >
          {dialog.kind === "order-details" && (
            <OrderDetails id={dialog.id} warehouses={warehouses} />
          )}
          {dialog.kind === "order" && (
            <OrderForm
              warehouses={warehouses}
              products={products}
              done={done}
            />
          )}
          {dialog.kind === "prepare" && (
            <PrepareForm orderId={dialog.order.id} done={done} />
          )}
          {dialog.kind === "history" && (
            <>
              <p className="help tracking-full">
                {dialog.shipment.trackingNumber}
              </p>
              <History id={dialog.shipment.id} />
            </>
          )}
          {dialog.kind === "status" && (
            <StatusForm shipment={dialog.shipment} done={done} />
          )}
          {dialog.kind === "confirm" && (
            <MutationForm
              button="Confirm"
              submit={async () => {
                await dialog.run();
                done();
              }}
            >
              <p className="help">{dialog.description}</p>
            </MutationForm>
          )}
        </Modal>
      )}
    </div>
  );
}
/** Inventory reads are scoped by the selected warehouse, never joined across tenants in the browser. */
function Inventory({
  canWrite,
  warehouses,
  products,
  revision,
  done,
}: {
  canWrite: boolean;
  warehouses: Warehouse[];
  products: Product[];
  revision: number;
  done: () => void;
}) {
  const [warehouseId, setWarehouse] = useState(""),
    [stock, setStock] = useState<Stock[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false);
  const [stockDialog, setStockDialog] = useState<{
    kind: "movement" | "history";
    warehouseId: string;
    productId: string;
  } | null>(null);
  const selected = warehouseId || warehouses[0]?.id || "";
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    api<Stock[]>(`/warehouses/${selected}/stock`, { signal: controller.signal })
      .then(setStock)
      .catch((error) => {
        if (!controller.signal.aborted) setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [selected, revision]);
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Warehouse inventory</h2>
          <p>Physical units, held units, and what’s available.</p>
        </div>
        <label className="inline-label">
          Warehouse
          <select
            value={selected}
            onChange={(e) => setWarehouse(e.target.value)}
          >
            <option value="" disabled>
              Select warehouse
            </option>
            {warehouses.map((warehouse) => (
              <option key={warehouse.id} value={warehouse.id}>
                {warehouse.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className={canWrite ? "inventory-layout" : "inventory-readonly"}>
        <div>
          {error ? (
            <p role="alert" className="form-error">
              {error}
            </p>
          ) : loading ? (
            <div className="loading" role="status">
              Loading stock…
            </div>
          ) : !stock.length ? (
            <Empty title="No stock at this location">
              Initialize opening stock to make products available for orders.
            </Empty>
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Product</th>
                    <th>On hand</th>
                    <th>Reserved</th>
                    <th>Available</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {stock.map((item) => (
                    <tr key={item.id}>
                      <td>
                        <strong>
                          {products.find(
                            (product) => product.id === item.productId,
                          )?.name || short(item.productId)}
                        </strong>
                        <small>
                          {
                            products.find(
                              (product) => product.id === item.productId,
                            )?.sku
                          }
                        </small>
                      </td>
                      <td>{item.onHand}</td>
                      <td>{item.reserved}</td>
                      <td>
                        <strong className="available">{item.available}</strong>
                      </td>
                      <td>
                        <div className="row-actions">
                          {canWrite && (
                            <button
                              onClick={() =>
                                setStockDialog({
                                  kind: "movement",
                                  warehouseId: selected,
                                  productId: item.productId,
                                })
                              }
                            >
                              Record movement
                            </button>
                          )}
                          <button
                            onClick={() =>
                              setStockDialog({
                                kind: "history",
                                warehouseId: selected,
                                productId: item.productId,
                              })
                            }
                          >
                            History
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        {canWrite && (
          <div className="stock-form">
            <h3>
              <ArrowDownLeft size={17} />
              Opening stock
            </h3>
            <OpeningForm
              key={`${selected}-${products.map((product) => product.id).join()}`}
              warehouseId={selected}
              products={products}
              done={done}
            />
          </div>
        )}
      </div>
      {stockDialog && (
        <Modal
          title={
            stockDialog.kind === "movement"
              ? "Record stock movement"
              : "Stock movement history"
          }
          close={() => setStockDialog(null)}
        >
          <p className="help">
            {products.find((product) => product.id === stockDialog.productId)
              ?.name || short(stockDialog.productId)}{" "}
            ·{" "}
            {
              warehouses.find(
                (warehouse) => warehouse.id === stockDialog.warehouseId,
              )?.name
            }
          </p>
          {stockDialog.kind === "movement" ? (
            <StockMovementForm
              warehouseId={stockDialog.warehouseId}
              productId={stockDialog.productId}
              done={() => {
                setStockDialog(null);
                done();
              }}
            />
          ) : (
            <MovementHistory
              warehouseId={stockDialog.warehouseId}
              productId={stockDialog.productId}
            />
          )}
        </Modal>
      )}
    </section>
  );
}
