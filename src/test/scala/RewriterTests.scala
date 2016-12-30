/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.nio.file.{Path, Paths}

import org.scalatest.{FunSuite, Matchers}
import viper.silver.ast._
import viper.silver.ast.utility._
import viper.silver.frontend.{SilFrontend, TranslatorState}
import viper.silver.verifier.AbstractError

import scala.collection.mutable
import scala.language.implicitConversions


class RewriterTests extends FunSuite with Matchers {

  test("TestingStuff") {
    val test1 = Assert(TrueLit()())()
    val test2 = Assert(TrueLit()())()

    println("eq: " + test1 eq test2) // false
    println("==: " + test1 == test2) // false

    val list = Seq(Assert(TrueLit()())(), Assert(TrueLit()())(), Assert(TrueLit()())(), test1)
    println(list.head == test1) // true
    println(list.indexWhere (_ eq test1)) // 3
    println(list.indexWhere (_ == test1)) // 0
  }

  test("ImplicationToDisjunctionTests") {
    val filePrefix = "transformations\\ImplicationsToDisjunction\\"
    val files = Seq("simple", "nested", "traverseEverything")

    // Create new strategy. Parameter is the partial function that is applied on all nodes
    val strat = new Strategy[Node]({
      case Implies(left, right) => Or(Not(left)(), right)()
    })

    val frontend = new DummyFrontend
    files foreach { name => executeTest(filePrefix, name, strat, frontend) }
  }

  test("DisjunctionToInhaleExhaleTests") {
    val filePrefix = "transformations\\DisjunctionToInhaleExhale\\"
    val files = Seq("simple"/*, "nested", "functions"*/)

    val strat = new StrategyC[Node, Seq[LocalVarDecl]]({
      case (Or(l, r), c) =>
        //val nonDet = NonDet(c, Bool) Cannot use this (silver angelic)
        c._2 match {
          case Seq() => InhaleExhaleExp(CondExp(TrueLit()(), l, r)(), Or(l, r)())()
          // Had to do variable renaming because otherwise variable would be quantified inside again with forall
          case varDecls => InhaleExhaleExp(CondExp(Forall(varDecls.map { vari => LocalVarDecl(vari.name + "Trafo", vari.typ)(vari.pos, vari.info) }, Seq(), TrueLit()())(), l, r)(), Or(l, r)())() // Placed true lit instead of nonDet
        }
    }) updateContext {
      case (q: QuantifiedExp, c) => c ++ q.variables
    } traverse Traverse.TopDown recurseFunc {
      case i: InhaleExhaleExp => Seq(true, false)
    } defaultContext Seq()

    val frontend = new DummyFrontend
    files foreach { name => executeTest(filePrefix, name, strat, frontend) }
  }

  test("WhileToIfAndGoto") {
    val filePrefix = "transformations\\WhileToIfAndGoto\\"
    val files = Seq("simple", "nested")

    // Example of how to transform a while loop into if and goto
    // Keeping metadata is awful when creating multiple statements from a single one and we need to think about this case, but at least it is possible
    var count = 0
    val strat = new Strategy[Node]({
      case w: While =>
        val invars: Exp = w.invs.reduce((x: Exp, y: Exp) => And(x, y)())
        count = count + 1
        Seqn(Seq(
          Assert(invars)(w.invs.head.pos, w.invs.head.info),
          If(Not(w.cond)(w.cond.pos, w.cond.info), Goto("skiploop" + count)(w.pos, w.info), Seqn(Seq())(w.pos, w.info))(w.pos, w.info),
          Label("loop" + count)(w.pos, w.info),
          w.body,
          Assert(invars)(w.invs.head.pos, w.invs.head.info),
          If(w.cond, Goto("loop" + count)(w.pos, w.info), Seqn(Seq())(w.pos, w.info))(w.pos, w.info),
          Label("skiploop" + count)(w.pos, w.info)
        ))(w.pos, w.info)

    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      count = 0
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("ManyToOneAssert") {
    val filePrefix = "transformations\\ManyToOneAssert\\"
    val files = Seq("simple", "interrupted", "nested", "nestedBlocks")

    val strat = new StrategyA[Node]({
      case (a: Assert, c) => {

        c.previous match {
          case Some(Assert(_)) => Seqn(Seq())() // If previous node is assertion we go to noop
          case _ => {
            // Otherwise we take all following assertions and merge their expressions into one
            c.successors.takeWhile(x => x match {
              // Take all following assertions
              case Assert(_) => true
              case _ => false
            }).collect({ case i: Assert => i }) match {
              // Collect works as a cast to list of assertion since take while does not do this
              case Seq() => a
              case as => {
                // Merge in case of multiple assertions
                val foldedExpr = as collect { case assertion => assertion.exp } reduceRight { (l, r) => And(l, r)() }
                Assert(And(a.exp, foldedExpr)())(a.pos, a.info)
              }
            }
          }
        }
      }
    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  /**
    * A second test for many to one assert that works differently form the other strategy.
    */
  test("ManyToOneAssert2") {
    val filePrefix = "transformations\\ManyToOneAssert\\"
    val files = Seq("simple", "interrupted", "nested", "nestedBlocks")
    var accumulator:mutable.ListBuffer[Exp] = mutable.ListBuffer.empty[Exp]
    val strat = new StrategyA[Node]({
      case (a: Assert, c) => {
        accumulator += a.exp

        c.next match {
          case Some(Assert(_)) => {
            Seqn(Seq())()
          }
          case _ => {
            val result = Assert(accumulator.reduceRight( And(_, _)()) )()
            accumulator.clear()
            result
          }
        }
      }
    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("FoldConstants") {
    val filePrefix = "transformations\\FoldConstants\\"
    val files = Seq("simple", "complex")

    // Only implemented int trasformations. its enough for the test
    val strat = new Strategy[Node]({
      case root@Add(i1:IntLit, i2:IntLit) => IntLit(i1.i + i2.i)(root.pos, root.info)
      case root@Sub(i1:IntLit, i2:IntLit) => IntLit(i1.i - i2.i)(root.pos, root.info)
      case root@Div(i1:IntLit, i2:IntLit) => if(i2.i != 0) IntLit(i1.i / i2.i)(root.pos, root.info) else root
      case root@Mul(i1:IntLit, i2:IntLit) => IntLit(i1.i * i2.i)(root.pos, root.info)
    }) traverse Traverse.BottomUp

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }

  }

  // This just tests functionality and is by no means a meaningful testcase
  test("UnfoldedChildren") {
    val filePrefix = "transformations\\UnfoldedChildren\\"
    val files = Seq("fourAnd")

    val strat = new StrategyC[Node, Int]({
      case (e:Exp, c) => c._1.parent match {
        case f:FuncApp => if(f.funcname == "fourAnd" && c._1.siblings.contains(FalseLit()())) {
          FalseLit()(e.pos, e.info)
        } else {
          e
        }
        case _ => e
      }
    }) traverse Traverse.BottomUp defaultContext 0

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }

  }

  test("CountAdditions") {
    val filePrefix = "transformations\\CountAdditions\\"
    val filesAndResults = Seq(("simple", 3), ("nested", 10), ("traverseEverything", 12))

    val query = new Query[Node, Int]({
      case a: Add => 1
    }) neutralElement 0 accumulate { (s: Seq[Int]) => s.sum }

    val frontend = new DummyFrontend

    filesAndResults foreach ((tuple) => {
      val fileName = tuple._1
      val result = tuple._2

      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      val file = Paths.get(fileRes.toURI)

      var targetNode: Node = null
      frontend.translate(file) match {
        case (Some(p), _) => {
          targetNode = p
        }
        case (None, errors) => println("Problem with program: " + errors)
      }
      val res = query.execute(targetNode)

      //      println("Actual: " + res)
      //      println("Expected: " + result)
      assert(res == result, "Results are not equal")
    })
  }

  test("MethodCallDesugaring") {
    // CAREFUL!! Don't use old inside postcondition
    val filePrefix = "transformations\\MethodCallDesugaring\\"
    val files = Seq("simple", "withArgs", "withArgsNRes", "withFields")

    val frontend = new DummyFrontend

    val symbolList = mutable.Map.empty[LocalVar, Exp]

    val stratRename = new Strategy[Node]({
      case l:LocalVar => if (symbolList.contains(l)) symbolList(l) else l
    })


    files foreach { fileName: String => {
      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      assert(fileRef != null, s"File $filePrefix$fileName Ref not found")
      val file = Paths.get(fileRes.toURI)
      val ref = Paths.get(fileRef.toURI)


      var targetNode: Program = null
      var targetRef: Program = null


      val strat = new Strategy[Node]({
        case m:MethodCall => {
          // Get method declaration
          val mDecl = targetNode.methods.find(_.name == m.methodName).get

          // Create an exhale statement for every precondition and replace parameters with arguments
          symbolList.clear()
          mDecl.formalArgs.map(_.localVar).zip(m.args).map( { (x:(LocalVar, Exp)) => symbolList.put(x._1, x._2) } )

          val exPres = mDecl.pres.map( stratRename.execute(_)).map( x => Exhale(x.asInstanceOf[Exp])(m.pos, m.info))

          // Create an inhale statement for every postcondition, replace parameters with arguments and replace result parameters with receivers
          val replacedArgs = mDecl.posts.map(stratRename.execute(_))
          symbolList.clear()
          mDecl.formalReturns.map(_.localVar).zip(m.targets).map( { (x:(LocalVar, Exp)) => symbolList.put(x._1, x._2) } )
          val inPosts = replacedArgs.map( stratRename.execute(_)).map( x => Inhale(x.asInstanceOf[Exp])(m.pos, m.info))

          Seqn(exPres ++ inPosts)(m.pos, m.info)
        }
      }) //traverse Traverse.Innermost


      frontend.translate(file) match {
        case (Some(p), _) => {
          targetNode = p
        }
        case (None, errors) => println("Problem with program: " + errors)
      }

      frontend.translate(ref) match {
        case (Some(p), _) => {
          targetRef = p
        }
        case (None, errors) => println("Problem with program: " + errors)
      }

      val res = strat.execute(targetNode)
      //  println("Old: " + targetNode.toString())
      println("New: " + res.toString())
      println("Reference: " + targetRef.toString())
      assert(res.toString() == targetRef.toString(), "Files are not equal")
    }
    }

  }

  def executeTest(filePrefix: String, fileName: String, strat: StrategyInterface[Node], frontend: DummyFrontend): Unit = {

    val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
    val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
    assert(fileRes != null, s"File $filePrefix$fileName not found")
    assert(fileRef != null, s"File $filePrefix$fileName Ref not found")
    val file = Paths.get(fileRes.toURI)
    val ref = Paths.get(fileRef.toURI)


    var targetNode: Node = null
    var targetRef: Node = null

    frontend.translate(file) match {
      case (Some(p), _) => {
        targetNode = p
      }
      case (None, errors) => println("Problem with program: " + errors)
    }

    frontend.translate(ref) match {
      case (Some(p), _) => {
        targetRef = p
      }
      case (None, errors) => println("Problem with program: " + errors)
    }

    val res = strat.execute(targetNode)
    //  println("Old: " + targetNode.toString())
      println("New: " + res.toString())
      println("Reference: " + targetRef.toString())
    assert(res.toString() == targetRef.toString(), "Files are not equal")
  }


  class DummyFrontend extends SilFrontend {
    def createVerifier(fullCmd: _root_.scala.Predef.String) = ???

    def configureVerifier(args: Seq[String]) = ???

    def translate(silverFile: Path): (Option[Program], Seq[AbstractError]) = {
      _verifier = None
      _state = TranslatorState.Initialized

      reset(silverFile) //
      translate()

      //println(s"_program = ${_program}") /* Option[Program], set if parsing and translating worked */
      //println(s"_errors = ${_errors}")   /*  Seq[AbstractError], contains errors, if encountered */

      (_program, _errors)
    }
  }

}
