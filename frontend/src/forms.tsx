import { useResource } from "./useResource";
import { ReadError } from "./details";
import { useEffect, useRef, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { api, post } from "./api";
import { MutationForm, useRetryKey } from "./components";
import type { Product, Shipment, Stock, Warehouse } from "./types";

/** Forms use real catalog IDs and send only quantities; tenant identity always stays server-owned. */
export function OrderForm({
  warehouses,
  products,
  done,
}: {
  warehouses: Warehouse[];
  products: Product[];
  done: () => void;
}) {
  const [warehouseId, setWarehouse] = useState(warehouses[0]?.id || "");
  const [reference, setReference] = useState(""),
    [items, setItems] = useState([
      { productId: products[0]?.id || "", quantity: 1 },
    ]);
  const [stock, setStock] = useState<Stock[]>([]),
    [stockError, setStockError] = useState("");
  const keyFor = useRetryKey();
  useEffect(() => {
    if (!warehouseId) return;
    const controller = new AbortController();
    setStock([]);
    setStockError("");
    api<Stock[]>(`/warehouses/${warehouseId}/stock`, {
      signal: controller.signal,
    })
      .then(setStock)
      .catch((error) => {
        if (error.name !== "AbortError") setStockError(error.message);
      });
    return () => controller.abort();
  }, [warehouseId]);
  if (!warehouses.length || !products.length)
    return (
      <p className="help">
        Add a warehouse, a product, and opening stock in Catalog & stock before
        creating your first order.
      </p>
    );
  return (
    <MutationForm
      button="Create order & reserve stock"
      submit={async () => {
        if (new Set(items.map((item) => item.productId)).size !== items.length)
          throw new Error("Choose each product only once.");
        const payload = {
          warehouseId,
          customerReference: reference.trim(),
          items: [...items].sort((a, b) =>
            a.productId.localeCompare(b.productId),
          ),
        };
        await post("/orders", { ...payload, requestId: keyFor(payload) });
        done();
      }}
    >
      <p className="help">
        Stock is reserved for all items together. If any item is unavailable,
        nothing is held.
      </p>
      <label>
        Customer reference
        <input
          required
          maxLength={100}
          value={reference}
          onChange={(e) => setReference(e.target.value)}
          placeholder="e.g. CUSTOMER-1042"
        />
      </label>
      <label>
        Warehouse
        <select
          value={warehouseId}
          onChange={(e) => setWarehouse(e.target.value)}
        >
          {warehouses.map((warehouse) => (
            <option key={warehouse.id} value={warehouse.id}>
              {warehouse.name} · {warehouse.city}
            </option>
          ))}
        </select>
      </label>
      <div className="form-section">Order items</div>
      {items.map((item, index) => (
        <div className="item-row" key={index}>
          <label>
            Product
            <select
              aria-label={`Product ${index + 1}`}
              value={item.productId}
              onChange={(e) =>
                setItems(
                  items.map((item, i) =>
                    i === index ? { ...item, productId: e.target.value } : item,
                  ),
                )
              }
            >
              {products.map((product) => (
                <option key={product.id} value={product.id}>
                  {product.sku} · {product.name}
                </option>
              ))}
            </select>
            <small>
              {stock.find((s) => s.productId === item.productId)?.available ??
                0}{" "}
              available here
            </small>
          </label>
          <label>
            Quantity
            <input
              aria-label={`Quantity ${index + 1}`}
              required
              type="number"
              min={1}
              max={2147483647}
              step={1}
              value={item.quantity}
              onChange={(e) =>
                setItems(
                  items.map((item, i) =>
                    i === index
                      ? { ...item, quantity: Number(e.target.value) }
                      : item,
                  ),
                )
              }
            />
          </label>
          <button
            type="button"
            className="icon-button"
            aria-label={`Remove item ${index + 1}`}
            disabled={items.length === 1}
            onClick={() => setItems(items.filter((_, i) => i !== index))}
          >
            <Trash2 size={17} />
          </button>
        </div>
      ))}
      {stockError && (
        <p className="form-error" role="alert">
          {stockError}
        </p>
      )}
      <button
        type="button"
        className="text-button"
        disabled={items.length >= Math.min(50, products.length)}
        onClick={() =>
          setItems([
            ...items,
            {
              productId: products.find(
                (product) =>
                  !items.some((item) => item.productId === product.id),
              )!.id,
              quantity: 1,
            },
          ])
        }
      >
        <Plus size={15} />
        Add item
      </button>
    </MutationForm>
  );
}
export function PrepareForm({
  orderId,
  done,
}: {
  orderId: string;
  done: () => void;
}) {
  const [carrier, setCarrier] = useState("");
  return (
    <MutationForm
      button="Prepare shipment"
      submit={async () => {
        await post("/shipments", { orderId, carrier });
        done();
      }}
    >
      <p className="help">
        Preparation keeps the stock hold in place. The carrier is a label; no
        external booking will be made.
      </p>
      <label>
        Carrier
        <input
          required
          maxLength={80}
          placeholder="e.g. Local delivery fleet"
          value={carrier}
          onChange={(e) => setCarrier(e.target.value)}
        />
      </label>
    </MutationForm>
  );
}
export function StatusForm({
  shipment,
  done,
}: {
  shipment: Shipment;
  done: () => void;
}) {
  const [note, setNote] = useState(""),
    keyFor = useRetryKey();
  const status = shipment.status === "DISPATCHED" ? "IN_TRANSIT" : "DELIVERED";
  return (
    <MutationForm
      button={status === "IN_TRANSIT" ? "Mark in transit" : "Mark delivered"}
      submit={async () => {
        const payload = { status, note: note.trim() };
        await post(`/shipments/${shipment.id}/status`, {
          ...payload,
          requestId: keyFor(payload),
        });
        done();
      }}
    >
      <p className="help">
        Record an operational update. This will appear in the shipment timeline.
      </p>
      <label>
        Delivery note
        <textarea
          required
          maxLength={250}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder={
            status === "IN_TRANSIT"
              ? "e.g. Departed the Berlin distribution hub"
              : "e.g. Handed to recipient at reception"
          }
        />
      </label>
    </MutationForm>
  );
}
export function CatalogForms({ done }: { done: () => void }) {
  const [name, setName] = useState(""),
    [city, setCity] = useState(""),
    [country, setCountry] = useState("DE");
  const [sku, setSku] = useState(""),
    [product, setProduct] = useState(""),
    [weight, setWeight] = useState("");
  return (
    <div className="two-column">
      <section className="panel">
        <div className="panel-heading">
          <h2>Add warehouse</h2>
        </div>
        <div className="panel-body">
          <MutationForm
            button="Add warehouse"
            submit={async () => {
              await post("/warehouses", { name, city, countryCode: country });
              setName("");
              setCity("");
              done();
            }}
          >
            <label>
              Warehouse name
              <input
                required
                maxLength={150}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <div className="two-fields">
              <label>
                City
                <input
                  required
                  maxLength={100}
                  value={city}
                  onChange={(e) => setCity(e.target.value)}
                />
              </label>
              <label>
                Country code
                <input
                  required
                  pattern="[A-Za-z]{2}"
                  maxLength={2}
                  value={country}
                  onChange={(e) => setCountry(e.target.value.toUpperCase())}
                />
              </label>
            </div>
          </MutationForm>
        </div>
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Add product</h2>
        </div>
        <div className="panel-body">
          <MutationForm
            button="Add product"
            submit={async () => {
              await post("/products", {
                sku,
                name: product,
                weightKg: Number(weight),
              });
              setSku("");
              setProduct("");
              setWeight("");
              done();
            }}
          >
            <label>
              Product name
              <input
                required
                maxLength={150}
                value={product}
                onChange={(e) => setProduct(e.target.value)}
              />
            </label>
            <div className="two-fields">
              <label>
                SKU
                <input
                  required
                  maxLength={64}
                  pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
                  value={sku}
                  onChange={(e) => setSku(e.target.value)}
                  placeholder="LAPTOP-001"
                />
              </label>
              <label>
                Weight (kg)
                <input
                  required
                  type="number"
                  min="0.001"
                  max="999999.999"
                  step="0.001"
                  value={weight}
                  onChange={(e) => setWeight(e.target.value)}
                />
              </label>
            </div>
          </MutationForm>
        </div>
      </section>
    </div>
  );
}
export function OpeningForm({
  warehouseId,
  products,
  done,
}: {
  warehouseId: string;
  products: Product[];
  done: () => void;
}) {
  const [productId, setProduct] = useState(products[0]?.id || ""),
    [quantity, setQuantity] = useState(0);
  if (!warehouseId || !products.length)
    return <p className="help">Add a warehouse and product first.</p>;
  return (
    <MutationForm
      button="Initialize stock"
      submit={async () => {
        await post(`/warehouses/${warehouseId}/stock`, {
          productId,
          initialQuantity: quantity,
        });
        done();
      }}
    >
      <p className="help">
        Opening stock can be set once per product and warehouse. Repeating it
        will not overwrite an existing balance.
      </p>
      <label>
        Product
        <select
          required
          value={productId}
          onChange={(e) => setProduct(e.target.value)}
        >
          {products.map((product) => (
            <option key={product.id} value={product.id}>
              {product.sku} · {product.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Opening quantity
        <input
          required
          type="number"
          min={0}
          max={2147483647}
          step={1}
          value={quantity}
          onChange={(e) => setQuantity(Number(e.target.value))}
        />
      </label>
    </MutationForm>
  );
}

/** Preview against a freshly loaded balance; the backend still checks locks, limits, and held units. */
export function StockMovementForm({
  warehouseId,
  productId,
  done,
}: {
  warehouseId: string;
  productId: string;
  done: () => void;
}) {
  const [type, setType] = useState<"RECEIPT" | "ADJUSTMENT">("RECEIPT");
  const [quantity, setQuantity] = useState("");
  const [reason, setReason] = useState("");
  const keyFor = useRetryKey();
  const lastSubmitted = useRef("");
  const {
    data: balance,
    loading,
    error,
    reload,
  } = useResource<Stock>(`/warehouses/${warehouseId}/stock/${productId}`);
  const delta = Number(quantity);
  const validNumber = quantity !== "" && Number.isInteger(delta) && delta !== 0;
  const after = balance && validNumber ? balance.onHand + delta : undefined;
  return (
    <MutationForm
      button="Record movement"
      disabled={loading || !balance}
      submit={async () => {
        if (!balance)
          throw new Error("Load the current balance before changing stock.");
        if (
          !validNumber ||
          delta < -2147483648 ||
          delta > 2147483647 ||
          (type === "RECEIPT" && delta < 0)
        )
          throw new Error(
            "Use a positive whole-number receipt or a nonzero signed adjustment.",
          );
        const payload = { type, quantity: delta, reason: reason.trim() };
        const fingerprint = JSON.stringify(payload);
        // A refreshed balance may already include a response-lost command. Let its original key replay on the server.
        const replay = lastSubmitted.current === fingerprint;
        if (!replay && balance.onHand + delta < balance.reserved)
          throw new Error(
            "This change would consume reserved stock. Refresh the balance or enter a smaller deduction.",
          );
        if (!replay && balance.onHand + delta > 2147483647)
          throw new Error(
            "This change exceeds the maximum supported stock balance.",
          );
        if (!payload.reason)
          throw new Error("A reason is required for the audit trail.");
        lastSubmitted.current = fingerprint;
        await post(`/warehouses/${warehouseId}/stock/${productId}/movements`, {
          ...payload,
          requestId: keyFor(payload),
        });
        done();
      }}
    >
      <p className="help">
        Receipts add delivered units. Adjustments record signed corrections.
        Every change keeps its reason and before/after balance.
      </p>
      {loading ? (
        <p role="status">Loading current balance…</p>
      ) : error ? (
        <ReadError message={error} retry={reload} />
      ) : (
        balance && (
          <div className="balance-preview">
            <span>
              On hand <strong>{balance.onHand}</strong>
            </span>
            <span>
              Reserved <strong>{balance.reserved}</strong>
            </span>
            <span>
              Available <strong>{balance.available}</strong>
            </span>
          </div>
        )
      )}
      <button type="button" className="text-button" onClick={reload}>
        Refresh balance
      </button>
      <label>
        Movement type
        <select
          value={type}
          onChange={(event) =>
            setType(event.target.value as "RECEIPT" | "ADJUSTMENT")
          }
        >
          <option value="RECEIPT">Receipt — add units</option>
          <option value="ADJUSTMENT">Adjustment — signed correction</option>
        </select>
      </label>
      <label>
        Quantity change
        <input
          required
          type="number"
          step={1}
          min={type === "RECEIPT" ? 1 : -2147483648}
          max={2147483647}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          placeholder={
            type === "RECEIPT" ? "e.g. 10" : "e.g. -2 for damaged units"
          }
        />
      </label>
      {after !== undefined && balance && (
        <p
          className={`change-preview ${after < balance.reserved || after > 2147483647 ? "invalid" : ""}`}
        >
          After this change: <strong>{after} on hand</strong>,{" "}
          {after - balance.reserved} available. The server rechecks the balance
          when saving.
        </p>
      )}
      <label>
        Reason
        <textarea
          required
          maxLength={250}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="e.g. Supplier delivery received, or 2 damaged units removed"
        />
      </label>
    </MutationForm>
  );
}
