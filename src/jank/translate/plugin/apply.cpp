#include <jank/translate/plugin/apply.hpp>
#include <jank/translate/plugin/io/print.hpp>
#include <jank/translate/plugin/arithmetic/add.hpp>
#include <jank/translate/plugin/arithmetic/subtract.hpp>
#include <jank/translate/plugin/arithmetic/multiply.hpp>
#include <jank/translate/plugin/arithmetic/divide.hpp>
#include <jank/translate/plugin/arithmetic/modulo.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_and.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_or.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_xor.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_not.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_left_shift.hpp>
#include <jank/translate/plugin/arithmetic/bitwise_right_shift.hpp>
#include <jank/translate/plugin/compare/equal.hpp>
#include <jank/translate/plugin/compare/less.hpp>
#include <jank/translate/plugin/compare/less_equal.hpp>
#include <jank/translate/plugin/compare/greater.hpp>
#include <jank/translate/plugin/compare/greater_equal.hpp>
#include <jank/translate/plugin/assertion/assertion.hpp>
#include <jank/translate/plugin/collection/list/cons.hpp>

namespace jank
{
  namespace translate
  {
    namespace plugin
    {
      std::shared_ptr<environment::scope> apply
      (std::shared_ptr<environment::scope> const &scope)
      {
        /* TODO: Read from shared objects. */
        std::vector
        <
          std::function<void (std::shared_ptr<environment::scope> const&)>
        > const plugins
        {
          &io::print,

          &arithmetic::add,
          &arithmetic::subtract,
          &arithmetic::multiply,
          &arithmetic::divide,
          &arithmetic::modulo,
          &arithmetic::bitwise_and,
          &arithmetic::bitwise_or,
          &arithmetic::bitwise_xor,
          &arithmetic::bitwise_not,
          &arithmetic::bitwise_left_shift,
          &arithmetic::bitwise_right_shift,
          &compare::equal,
          &compare::less,
          &compare::less_equal,
          &compare::greater,
          &compare::greater_equal,
          &assertion::assertion,
          &collection::list::cons,
        };

        for(auto const &plugin : plugins)
        { plugin(scope); }

        return scope;
      }
    }
  }
}
