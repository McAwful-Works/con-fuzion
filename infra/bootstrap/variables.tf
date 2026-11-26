variable "monthly_budget_usd" {
  description = "The monthly AWS cost budget in USD, set by the owner. There's no default, so a plan can't run without it."
  type        = number

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd must be a positive number of US dollars."
  }
}
