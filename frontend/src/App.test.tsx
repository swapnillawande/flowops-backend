import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { OrderForm, StatusForm } from "./forms";
import { History } from "./components";
import { api, post } from "./api";
import type { Shipment } from "./types";

vi.mock("./api", () => ({ api: vi.fn(), post: vi.fn() }));
const warehouse = {
  id: "warehouse-1",
  name: "Berlin Central",
  city: "Berlin",
  countryCode: "DE",
};
const product = { id: "product-1", name: "Laptop", sku: "LAPTOP", weightKg: 1 };
const shipment: Shipment = {
  id: "shipment-1",
  orderId: "order-1",
  warehouseId: warehouse.id,
  carrier: "Local fleet",
  trackingNumber: "FLO-123",
  status: "DISPATCHED",
  createdAt: "2026-10-21T10:00:00Z",
};
beforeEach(() => {
  vi.mocked(api).mockReset();
  vi.mocked(post).mockReset();
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith("/orders"))
      return { content: [], totalElements: 123, totalPages: 13 } as never;
    if (path.startsWith("/shipments"))
      return { content: [], totalElements: 45, totalPages: 5 } as never;
    if (path === "/warehouses") return [warehouse] as never;
    if (path === "/products") return [product] as never;
    return [{ productId: product.id, available: 10 }] as never;
  });
});
describe("Operations console", () => {
  it("displays API totals, not invented operational metrics", async () => {
    render(<App />);
    expect(await screen.findByText("123")).toBeInTheDocument();
    expect(screen.getByText("45")).toBeInTheDocument();
    expect(screen.getByText("No orders yet")).toBeInTheDocument();
    expect(screen.getByText("Connected to backend")).toBeInTheDocument();
  });
  it("shows a connection error and disables new commands on a failed initial load", async () => {
    vi.mocked(api).mockRejectedValue(new Error("Backend offline"));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Backend offline",
    );
    expect(screen.getByRole("button", { name: "New order" })).toBeDisabled();
  });
  it("retains the order request ID after a failed submit to avoid double reservation", async () => {
    vi.mocked(post)
      .mockRejectedValueOnce(new Error("Connection interrupted"))
      .mockResolvedValueOnce({});
    const done = vi.fn(),
      user = userEvent.setup();
    render(
      <OrderForm warehouses={[warehouse]} products={[product]} done={done} />,
    );
    await user.type(screen.getByLabelText("Customer reference"), "CUSTOMER-42");
    await user.click(
      screen.getByRole("button", { name: "Create order & reserve stock" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Connection interrupted",
    );
    const first = vi.mocked(post).mock.calls[0][1] as { requestId: string };
    await user.click(
      screen.getByRole("button", { name: "Create order & reserve stock" }),
    );
    await waitFor(() => expect(done).toHaveBeenCalledOnce());
    expect(
      (vi.mocked(post).mock.calls[1][1] as { requestId: string }).requestId,
    ).toBe(first.requestId);
  });
  it("guides an empty workspace toward catalog setup", () => {
    render(<OrderForm warehouses={[]} products={[]} done={vi.fn()} />);
    expect(screen.getByText(/Add a warehouse, a product/)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create order & reserve stock" }),
    ).not.toBeInTheDocument();
  });
  it("only offers the next valid delivery transition and trims its note", async () => {
    vi.mocked(post).mockResolvedValue({});
    const user = userEvent.setup();
    render(<StatusForm shipment={shipment} done={vi.fn()} />);
    await user.type(screen.getByLabelText("Delivery note"), " Departed hub ");
    await user.click(screen.getByRole("button", { name: "Mark in transit" }));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        "/shipments/shipment-1/status",
        expect.objectContaining({ status: "IN_TRANSIT", note: "Departed hub" }),
      ),
    );
    expect(
      screen.queryByRole("button", { name: "Mark delivered" }),
    ).not.toBeInTheDocument();
  });
  it("renders server history and escapes note content as plain text", async () => {
    vi.mocked(api).mockResolvedValue([
      {
        id: "event-1",
        status: "IN_TRANSIT",
        note: "<script>not executable</script>",
        createdAt: shipment.createdAt,
        sequence: 1,
      },
    ]);
    render(<History id={shipment.id} />);
    expect(
      await screen.findByText("<script>not executable</script>"),
    ).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
  });
});

describe("Viewer permissions", () => {
  const session = {
    subject: "viewer",
    username: "berlin.viewer",
    tenantId: "tenant-1",
    roles: ["viewer" as const],
  };
  it("keeps order and shipment history available while hiding fulfillment controls", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      if (path.startsWith("/orders"))
        return {
          content: [
            {
              id: "order-1",
              warehouseId: warehouse.id,
              customerReference: "Customer",
              status: "CONFIRMED",
              createdAt: "2026-10-22T10:00:00Z",
              items: [],
            },
          ],
          totalElements: 1,
          totalPages: 1,
        } as never;
      if (path.startsWith("/shipments"))
        return {
          content: [shipment],
          totalElements: 1,
          totalPages: 1,
        } as never;
      if (path === "/warehouses") return [warehouse] as never;
      return [product] as never;
    });
    render(<App session={session} />);
    expect(
      await screen.findByText("Read-only access", { exact: false }),
    ).toBeInTheDocument();
    await screen.findByText("Customer");
    expect(
      screen.queryByRole("button", { name: "New order" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Prepare shipment" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Mark in transit" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "View history" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /#order/i })).toBeInTheDocument();
  });
  it("allows stock and ledger browsing without catalog or balance forms", async () => {
    render(<App session={session} />);
    await screen.findByText("123");
    await userEvent.click(
      screen.getByRole("button", { name: "Catalog & stock" }),
    );
    expect(
      await screen.findByRole("button", { name: "History" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Record movement" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Add warehouse" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Add product" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Opening quantity")).not.toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });
});
