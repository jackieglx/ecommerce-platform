import { z } from 'zod'
export const checkoutSchema = z.object({ skuId: z.string().min(1, '请输入 SKU ID'), qty: z.coerce.number().int().min(1, '数量至少为 1'), userId: z.string().min(1, '请输入用户 ID') })
export type CheckoutInput = z.infer<typeof checkoutSchema>
