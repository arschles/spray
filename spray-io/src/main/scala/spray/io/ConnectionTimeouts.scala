/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.io

import scala.concurrent.duration.Duration
import akka.io.Tcp
import spray.util.{ Timestamp, requirePositive }

object ConnectionTimeouts {

  def apply(idleTimeout: Duration): PipelineStage = {
    requirePositive(idleTimeout)

    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
        var timeout = idleTimeout
        var idleDeadline = Timestamp.never
        def refreshDeadline() = idleDeadline = Timestamp.now + timeout
        refreshDeadline()

        val commandPipeline: CPL = {
          case x: Tcp.Write      ⇒ commandPL(x); refreshDeadline()
          case SetIdleTimeout(x) ⇒ timeout = x; refreshDeadline()
          case cmd               ⇒ commandPL(cmd)
        }

        val eventPipeline: EPL = {
          case x: Tcp.Received ⇒ refreshDeadline(); eventPL(x)
          case tick @ TickGenerator.Tick ⇒
            if (idleDeadline.isPast) {
              context.log.debug("Closing connection due to idle timeout...")
              commandPL(Tcp.Close)
            }
            eventPL(tick)

          case ev ⇒ eventPL(ev)
        }
      }
    }
  }

  ////////////// COMMANDS //////////////

  case class SetIdleTimeout(timeout: Duration) extends Command {
    requirePositive(timeout)
  }
}
