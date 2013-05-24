package semper.sil.ast.utility

import semper.sil.ast._

/**
 * An implementation for transformers of the SIL AST.
 *
 * @author Stefan Heule
 */
object Transformer {

  /**
   * See Exp.transform.
   */
  def transform(exp: Exp,
    pre: PartialFunction[Exp, Exp] = PartialFunction.empty)(
      recursive: Exp => Boolean = !pre.isDefinedAt(_),
      post: PartialFunction[Exp, Exp] = PartialFunction.empty): Exp = {
    val p = exp.pos
    val i = exp.info
    val beforeRecursion = pre.applyOrElse(exp, identity[Exp])
    val afterRecursion = if (recursive(exp)) {
      val func = (e: Exp) => transform(e, pre)(recursive, post)
      exp match {
        case IntLit(_) => exp
        case BoolLit(_) => exp
        case NullLit() => exp
        case AbstractLocalVar(_) => exp
        case FieldAccess(rcv, field) => FieldAccess(func(rcv), field)(p, i)
        case PredicateAccess(rcv, predicate) => PredicateAccess(func(rcv), predicate)(p, i)
        case Unfolding(acc, e) => Unfolding(func(acc).asInstanceOf[PredicateAccessPredicate], func(e))(p, i)
        case Old(e) => Old(func(e))(p, i)
        case CondExp(cond, thn, els) => CondExp(func(cond), func(thn), func(els))(p, i)
        case Exists(v, e) => Exists(v, func(e))(p, i)
        case Forall(v, triggers, e) =>
          Forall(v,
            triggers map (t => Trigger(t.exps map func)(t.pos, t.info)),
            func(e))(p, i)
        case InhaleExhaleExp(in, ex) => InhaleExhaleExp(func(in), func(ex))(p, i)
        case WildcardPerm() => exp
        case FullPerm() => exp
        case NoPerm() => exp
        case EpsilonPerm() => exp
        case CurrentPerm(loc) => CurrentPerm(func(loc).asInstanceOf[LocationAccess])(p, i)
        case FractionalPerm(left, right) => FractionalPerm(func(left), func(right))(p, i)
        case FieldAccessPredicate(loc, perm) =>
          FieldAccessPredicate(func(loc).asInstanceOf[FieldAccess], func(perm))(p, i)
        case PredicateAccessPredicate(loc, perm) =>
          PredicateAccessPredicate(func(loc).asInstanceOf[PredicateAccess], func(perm))(p, i)
        case FuncApp(ff, args) => FuncApp(ff, args map func)(p, i)
        case DomainFuncApp(ff, args, m) => DomainFuncApp(ff, args map func, m)(p, i)

        case Neg(e) => Neg(func(e))(p, i)
        case Not(e) => Not(func(e))(p, i)

        case Or(l, r) => Or(func(l), func(r))(p, i)
        case And(l, r) => And(func(l), func(r))(p, i)
        case Implies(l, r) => Implies(func(l), func(r))(p, i)

        case Add(l, r) => Add(func(l), func(r))(p, i)
        case Sub(l, r) => Sub(func(l), func(r))(p, i)
        case Mul(l, r) => Mul(func(l), func(r))(p, i)
        case Div(l, r) => Div(func(l), func(r))(p, i)
        case Mod(l, r) => Mod(func(l), func(r))(p, i)

        case LtCmp(l, r) => LtCmp(func(l), func(r))(p, i)
        case LeCmp(l, r) => LeCmp(func(l), func(r))(p, i)
        case GtCmp(l, r) => GtCmp(func(l), func(r))(p, i)
        case GeCmp(l, r) => GeCmp(func(l), func(r))(p, i)

        case EqCmp(l, r) => EqCmp(func(l), func(r))(p, i)
        case NeCmp(l, r) => NeCmp(func(l), func(r))(p, i)

        case PermAdd(l, r) => PermAdd(func(l), func(r))(p, i)
        case PermSub(l, r) => PermSub(func(l), func(r))(p, i)
        case PermMul(l, r) => PermMul(func(l), func(r))(p, i)
        case IntPermMul(l, r) => IntPermMul(func(l), func(r))(p, i)

        case PermLtCmp(l, r) => PermLtCmp(func(l), func(r))(p, i)
        case PermLeCmp(l, r) => PermLeCmp(func(l), func(r))(p, i)
        case PermGtCmp(l, r) => PermGtCmp(func(l), func(r))(p, i)
        case PermGeCmp(l, r) => PermGeCmp(func(l), func(r))(p, i)

        case EmptySeq(elemTyp) => exp
        case ExplicitSeq(elems) => ExplicitSeq(elems map func)(p, i)
        case RangeSeq(low, high) => RangeSeq(func(low), func(high))(p, i)
        case SeqAppend(left, right) => SeqAppend(func(left), func(right))(p, i)
        case SeqIndex(seq, idx) => SeqIndex(func(seq), func(idx))(p, i)
        case SeqTake(seq, n) => SeqTake(func(seq), func(n))(p, i)
        case SeqDrop(seq, n) => SeqDrop(func(seq), func(n))(p, i)
        case SeqContains(elem, seq) => SeqContains(func(elem), func(seq))(p, i)
        case SeqUpdate(seq, idx, elem) => SeqUpdate(func(seq), func(idx), func(elem))(p, i)
        case SeqLength(seq) => SeqLength(func(seq))(p, i)
      }
    } else {
      beforeRecursion
    }
    post.applyOrElse(afterRecursion, identity[Exp])
  }

  /**
   * Apply transformation to general node of program. If partial function is
   * defined at certain node, replace it. Otherwise, recursively transform
   * children nodes. This can be useful to transform entire programs.
   *
   * @param node    Root of tree to transform.
   * @param replace Partial function for replacement, if any. It should make
   *                sure program is still valid.
   *
   * @return Transformed tree.
   *
   * @see transform(Exp, PartialFunction[Exp, Exp])(Exp => Boolean,
   *        PartialFunction[Exp, Exp])
   */
  def transformNode(node: Node, replace: PartialFunction[Node, Node]): Node = {
    def go(other: Node): Node = {
      transformNode(other, replace)
    }
    val transformExpression = replace.asInstanceOf[PartialFunction[Exp, Exp]]
    def goExpression(expression: Exp): Exp = {
      expression.transform(transformExpression)()
    }

    def transform(other: Node): Node = {
      other match {
        case root @ Program(domains, fields, functions, predicates, methods) =>
          Program(domains.map(go).asInstanceOf[Seq[Domain]],
            fields.map(go).asInstanceOf[Seq[Field]],
            functions.map(go).asInstanceOf[Seq[Function]],
            predicates.map(go).asInstanceOf[Seq[Predicate]],
            methods.map(go).asInstanceOf[Seq[Method]])(root.pos,
              root.info)

        case member: Member =>
          member match {
            case root @ Domain(name, functions, axioms, typeVariables) =>
              Domain(name, functions.map(go).asInstanceOf[Seq[DomainFunc]],
                axioms.map(go).asInstanceOf[Seq[DomainAxiom]],
                typeVariables.map(go).asInstanceOf[Seq[TypeVar]])(root.pos,
                  root.info)

            case root @ Field(name, singleType) =>
              Field(name,
                go(singleType).asInstanceOf[Type])(root.pos, root.info)

            case root @ Function(name, parameters, singleType, preconditions,
              postconditions, body) =>
              Function(name,
                parameters.map(go).asInstanceOf[Seq[LocalVarDecl]],
                singleType, preconditions.map(goExpression),
                postconditions.map(goExpression), goExpression(body))(root.pos,
                  root.info)

            case root @ Predicate(name, parameter, body) =>
              Predicate(name, go(parameter).asInstanceOf[LocalVarDecl],
                goExpression(body))(root.pos, root.info)

            case root @ Method(name, parameters, results, preconditions,
              postconditions, locals, body) =>
              Method(name, parameters.map(go).asInstanceOf[Seq[LocalVarDecl]],
                results.map(go).asInstanceOf[Seq[LocalVarDecl]],
                preconditions.map(goExpression),
                postconditions.map(goExpression),
                locals.map(go).asInstanceOf[Seq[LocalVarDecl]],
                go(body).asInstanceOf[Stmt])(root.pos, root.info)
          }

        // TODO: Working here for cases.
        case domainMember: DomainMember =>
          domainMember match {
            case root @ DomainAxiom(name, body) =>
              DomainAxiom(name, goExpression(body))(root.pos, root.info)

            case root @ DomainFunc(name, parameters, singleType, unique) =>
              DomainFunc(name,
                parameters.map(go).asInstanceOf[Seq[LocalVarDecl]], singleType,
                unique)(root.pos, root.info)
          }

        case singleType: Type =>
          singleType match {
            case root @ Bool => root

            case DomainType(domain, typeVariables) =>
              DomainType(domain, typeVariables.toSeq.map(mapping => {
                val (fromVariable, toType) = mapping
                (go(fromVariable).asInstanceOf[TypeVar],
                  go(toType).asInstanceOf[Type])
              }).toMap)

            case root @ Int => root
            case root @ Perm => root
            case root @ Pred => root
            case root @ Ref => root

            case SeqType(elementType) =>
              SeqType(go(elementType).asInstanceOf[Type])

            case root @ TypeVar(_) => root
          }

        case root @ LocalVarDecl(name, singleType) =>
          LocalVarDecl(name,
            go(singleType).asInstanceOf[Type])(root.pos, root.info)

        case expression: Exp => goExpression(expression)

        case statement: Stmt =>
          statement match {
            case root @ Assert(expression) =>
              Assert(goExpression(expression))(root.pos, root.info)
          }

        // TODO. case root @ () => ()(root.pos, root.info)
      }
    }

    replace.applyOrElse(node, transform)
  }

  /**
   * Simplify `expression`, in particular by making use of literals. For
   * example, `!true` is replaced by `false`. Division and modulo with divisor
   * 0 are not treated. Nonterminating expression due to endless recursion
   * might be transformed to terminating expression.
   */
  def simplify(expression: Exp): Exp = {
    /* Always simplify children first, then treat parent. */
    transform(expression)(_ => true, {
      case root @ Not(BoolLit(literal)) =>
        BoolLit(!literal)(root.pos, root.info)
      case Not(Not(single)) => single

      case And(TrueLit(), right) => right
      case And(left, TrueLit()) => left
      case root @ And(FalseLit(), _) => FalseLit()(root.pos, root.info)
      case root @ And(_, FalseLit()) => FalseLit()(root.pos, root.info)

      case Or(FalseLit(), right) => right
      case Or(left, FalseLit()) => left
      case root @ Or(TrueLit(), _) => TrueLit()(root.pos, root.info)
      case root @ Or(_, TrueLit()) => TrueLit()(root.pos, root.info)

      case root @ Implies(FalseLit(), _) => TrueLit()(root.pos, root.info)
      case root @ Implies(_, TrueLit()) => TrueLit()(root.pos, root.info)
      case root @ Implies(TrueLit(), FalseLit()) =>
        FalseLit()(root.pos, root.info)
      case Implies(TrueLit(), consequent) => consequent

      case root @ EqCmp(BoolLit(left), BoolLit(right)) =>
        BoolLit(left == right)(root.pos, root.info)
      case root @ EqCmp(FalseLit(), right) => Not(right)(root.pos, root.info)
      case root @ EqCmp(left, FalseLit()) => Not(left)(root.pos, root.info)
      case EqCmp(TrueLit(), right) => right
      case EqCmp(left, TrueLit()) => left
      case root @ EqCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left == right)(root.pos, root.info)
      case root @ EqCmp(NullLit(), NullLit()) => TrueLit()(root.pos, root.info)

      case root @ NeCmp(BoolLit(left), BoolLit(right)) =>
        BoolLit(left != right)(root.pos, root.info)
      case NeCmp(FalseLit(), right) => right
      case NeCmp(left, FalseLit()) => left
      case root @ NeCmp(TrueLit(), right) => Not(right)(root.pos, root.info)
      case root @ NeCmp(left, TrueLit()) => Not(left)(root.pos, root.info)
      case root @ NeCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left != right)(root.pos, root.info)
      case root @ NeCmp(NullLit(), NullLit()) => FalseLit()(root.pos, root.info)

      case CondExp(TrueLit(), ifTrue, _) => ifTrue
      case CondExp(FalseLit(), _, ifFalse) => ifFalse
      case root @ CondExp(_, FalseLit(), FalseLit()) =>
        FalseLit()(root.pos, root.info)
      case root @ CondExp(_, TrueLit(), TrueLit()) =>
        TrueLit()(root.pos, root.info)
      case root @ CondExp(condition, FalseLit(), TrueLit()) =>
        Not(condition)(root.pos, root.info)
      case CondExp(condition, TrueLit(), FalseLit()) => condition
      case root @ CondExp(condition, FalseLit(), ifFalse) =>
        And(Not(condition)(), ifFalse)(root.pos, root.info)
      case root @ CondExp(condition, TrueLit(), ifFalse) =>
        Or(condition, ifFalse)(root.pos, root.info)
      case root @ CondExp(condition, ifTrue, FalseLit()) =>
        And(condition, ifTrue)(root.pos, root.info)
      case root @ CondExp(condition, ifTrue, TrueLit()) =>
        Or(Not(condition)(), ifTrue)(root.pos, root.info)

      case root @ Forall(_, _, BoolLit(literal)) =>
        BoolLit(literal)(root.pos, root.info)
      case root @ Exists(_, BoolLit(literal)) =>
        BoolLit(literal)(root.pos, root.info)

      case root @ Neg(IntLit(literal)) => IntLit(-literal)(root.pos, root.info)
      case Neg(Neg(single)) => single

      case root @ GeCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left >= right)(root.pos, root.info)
      case root @ GtCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left > right)(root.pos, root.info)
      case root @ LeCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left <= right)(root.pos, root.info)
      case root @ LtCmp(IntLit(left), IntLit(right)) =>
        BoolLit(left < right)(root.pos, root.info)

      case root @ Add(IntLit(left), IntLit(right)) =>
        IntLit(left + right)(root.pos, root.info)
      case root @ Sub(IntLit(left), IntLit(right)) =>
        IntLit(left - right)(root.pos, root.info)
      case root @ Mul(IntLit(left), IntLit(right)) =>
        IntLit(left * right)(root.pos, root.info)
      case root @ Div(IntLit(left), IntLit(right)) if right != 0 =>
        IntLit(left / right)(root.pos, root.info)
      case root @ Mod(IntLit(left), IntLit(right)) if right != 0 =>
        IntLit(left % right)(root.pos, root.info)
    })
  }
}
