import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrderDetails, MovementHistory } from "./details";
import { StockMovementForm } from "./forms";
import { api, post } from "./api";
vi.mock("./api", () => ({ api: vi.fn(), post: vi.fn() }));
beforeEach(() => {
  vi.mocked(api).mockReset();
  vi.mocked(post).mockReset();
});
const balance = { onHand: 10, reserved: 5, available: 5 };
const fixture = {
  id: "order-1",
  requestId: "request-1",
  reservationId: "hold-1",
  warehouseId: "warehouse-1",
  customerReference: "CUSTOMER-42",
  status: "CONFIRMED",
  createdAt: "2026-10-21T10:00:00Z",
  items: [
    {
      productId: "product-1",
      sku: "ORIGINAL-SKU",
      productName: "Original name",
      quantity: 3,
    },
  ],
};
const movementForm = () =>
  render(
    <StockMovementForm
      warehouseId="warehouse-1"
      productId="product-1"
      done={vi.fn()}
    />,
  );
describe("Order and inventory details", () => {
  it("loads the canonical order and displays its original item snapshots", async () => {
    vi.mocked(api).mockResolvedValue(fixture);
    render(
      <OrderDetails
        id="order-1"
        warehouses={[
          {
            id: "warehouse-1",
            name: "Berlin Central",
            city: "Berlin",
            countryCode: "DE",
          },
        ]}
      />,
    );
    expect(await screen.findByText("Original name")).toBeInTheDocument();
    expect(screen.getByText("ORIGINAL-SKU")).toBeInTheDocument();
    expect(screen.getByText("hold-1")).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith("/orders/order-1", expect.anything());
  });
  it("allows recovery from a failed detail read", async () => {
    vi.mocked(api)
      .mockRejectedValueOnce(new Error("Connection interrupted"))
      .mockResolvedValueOnce(fixture);
    render(<OrderDetails id="order-1" warehouses={[]} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Connection interrupted",
    );
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Original name")).toBeInTheDocument();
  });
  it("shows signed ledger changes, balances and escaped audit reasons", async () => {
    vi.mocked(api).mockResolvedValue([
      {
        id: "movement-1",
        type: "ADJUSTMENT",
        quantity: -2,
        onHandBefore: 10,
        onHandAfter: 8,
        reason: "<script>plain text</script>",
        createdAt: fixture.createdAt,
        shipmentId: null,
      },
    ]);
    render(<MovementHistory warehouseId="warehouse-1" productId="product-1" />);
    expect(await screen.findByText("-2 units")).toBeInTheDocument();
    expect(screen.getByText("10 → 8 on hand")).toBeInTheDocument();
    expect(screen.getByText("<script>plain text</script>")).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
  });
  it("handles an empty ledger without inventing entries", async () => {
    vi.mocked(api).mockResolvedValue([]);
    render(<MovementHistory warehouseId="warehouse-1" productId="product-1" />);
    expect(await screen.findByText("No movement history")).toBeInTheDocument();
  });
  it("sends receipts with a trimmed reason and stable key across failures", async () => {
    vi.mocked(api).mockResolvedValue(balance);
    vi.mocked(post)
      .mockRejectedValueOnce(new Error("Connection interrupted"))
      .mockResolvedValueOnce({});
    movementForm();
    const user = userEvent.setup();
    await screen.findByText("Available");
    await user.type(screen.getByLabelText("Quantity change"), "3");
    await user.type(screen.getByLabelText("Reason"), " Supplier delivery ");
    await user.click(screen.getByRole("button", { name: "Record movement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Connection interrupted",
    );
    const first = vi.mocked(post).mock.calls[0][1] as { requestId: string };
    await user.click(screen.getByRole("button", { name: "Record movement" }));
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post).toHaveBeenLastCalledWith(
      "/warehouses/warehouse-1/stock/product-1/movements",
      expect.objectContaining({
        type: "RECEIPT",
        quantity: 3,
        reason: "Supplier delivery",
        requestId: first.requestId,
      }),
    );
  });
  it("rejects a new adjustment that would consume reserved stock", async () => {
    vi.mocked(api).mockResolvedValue(balance);
    movementForm();
    const user = userEvent.setup();
    await screen.findByText("Available");
    await user.selectOptions(
      screen.getByLabelText("Movement type"),
      "ADJUSTMENT",
    );
    await user.type(screen.getByLabelText("Quantity change"), "-6");
    await user.type(screen.getByLabelText("Reason"), "Damaged units");
    await user.click(screen.getByRole("button", { name: "Record movement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "consume reserved stock",
    );
    expect(post).not.toHaveBeenCalled();
  });
  it("lets the backend replay a response-lost deduction after balance refresh", async () => {
    vi.mocked(api)
      .mockResolvedValueOnce(balance)
      .mockResolvedValueOnce({ onHand: 5, reserved: 5, available: 0 });
    vi.mocked(post)
      .mockRejectedValueOnce(new Error("Response lost"))
      .mockResolvedValueOnce({});
    movementForm();
    const user = userEvent.setup();
    await screen.findByText("Available");
    await user.selectOptions(
      screen.getByLabelText("Movement type"),
      "ADJUSTMENT",
    );
    await user.type(screen.getByLabelText("Quantity change"), "-5");
    await user.type(screen.getByLabelText("Reason"), "Correction");
    await user.click(screen.getByRole("button", { name: "Record movement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Response lost");
    const original = vi.mocked(post).mock.calls[0][1];
    await user.click(screen.getByRole("button", { name: "Refresh balance" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Record movement" }),
      ).toBeEnabled(),
    );
    await user.click(screen.getByRole("button", { name: "Record movement" }));
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(vi.mocked(post).mock.calls[1][1]).toEqual(original);
  });
  it("disables mutation when the current balance cannot be loaded", async () => {
    vi.mocked(api).mockRejectedValue(new Error("Stock not found"));
    movementForm();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Stock not found",
    );
    expect(
      screen.getByRole("button", { name: "Record movement" }),
    ).toBeDisabled();
  });
});
