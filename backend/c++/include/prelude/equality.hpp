#pragma once

#include "primitive.hpp"

namespace jank
{
  template <typename LHS, typename RHS>
  auto _gen_equal(LHS &&lhs, RHS &&rhs)
  { return lhs == rhs; }

  template <typename LHS, typename RHS>
  auto _gen_less_gen_equal(LHS &&lhs, RHS &&rhs)
  { return lhs <= rhs; }
}
