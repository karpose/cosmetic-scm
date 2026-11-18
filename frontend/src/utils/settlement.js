import { today } from './format'

export function newInvoiceItem(statement, amount) {
  const rates = statement.taxBreakdown ?? []
  return {
    invoiceNo: '',
    invoiceType: Number(statement.totalAmount) < 0 ? 'RED' : 'BLUE',
    invoiceDate: today(),
    taxRate: rates.length === 1 ? Number(rates[0].taxRate) : undefined,
    amount
  }
}

export function invoiceDraft(statement) {
  const returned = statement.invoiceCorrectionPending ? statement.invoiceReturns?.[0] : null
  if (returned?.invoices.length) {
    return returned.invoices.map(({ invoiceNo, invoiceType, invoiceDate, taxRate, amount, note }) => ({
      invoiceNo, invoiceType, invoiceDate, taxRate: Number(taxRate), amount: Number(amount), note
    }))
  }
  const item = newInvoiceItem(statement, Number(statement.totalAmount))
  if (returned?.invoiceNo) {
    item.invoiceNo = returned.invoiceNo
    item.invoiceDate = returned.invoiceDate
  }
  return [item]
}

export function invoicesComplete(items) {
  return items.every((item) => item.invoiceNo.trim() && item.invoiceDate
    && Number.isFinite(item.taxRate) && Number.isFinite(item.amount) && item.amount !== 0)
}
