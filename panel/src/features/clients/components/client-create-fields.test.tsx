import { useState } from "react"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"

import {
  ClientCreateFields,
  type ClientCreateFieldsValue,
} from "@/features/clients/components/client-create-fields"

const INITIAL_VALUE: ClientCreateFieldsValue = {
  clientType: "INDIVIDUAL",
  displayName: "",
  phone: "",
  contactPerson: "",
  email: "",
  comment: "",
  source: "",
}

function FieldsHarness() {
  const [value, setValue] = useState(INITIAL_VALUE)

  return (
    <ClientCreateFields
      idPrefix="test-client"
      value={value}
      responsibleManagerDisplayName="Мария Менеджер"
      onChange={setValue}
    />
  )
}

describe("ClientCreateFields", () => {
  it("offers only individual and legal-entity choices and requires a legal contact", async () => {
    const user = userEvent.setup()
    render(<FieldsHarness />)

    expect(screen.getByRole("radio", { name: "Физическое лицо" })).toBeTruthy()
    expect(screen.getByRole("radio", { name: "Юридическое лицо" })).toBeTruthy()
    expect(screen.queryByRole("radio", { name: "ИП" })).toBeNull()
    expect(screen.queryByLabelText("Основное контактное лицо")).toBeNull()

    await user.click(screen.getByRole("radio", { name: "Юридическое лицо" }))

    const contact = screen.getByLabelText(
      "Основное контактное лицо"
    ) as HTMLInputElement
    expect(contact.required).toBe(true)
    const manager = screen.getByLabelText(
      "Ответственный менеджер"
    ) as HTMLInputElement
    expect(manager.value).toBe("Мария Менеджер")
    expect(manager.readOnly).toBe(true)
    expect(manager.required).toBe(true)
  })
})
