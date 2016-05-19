package nasa.nccs.esgf.process
import java.util.Formatter

import nasa.nccs.cdapi.cdm._
import ucar.nc2.dataset.{CoordinateAxis, CoordinateAxis1D, CoordinateAxis1DTime, VariableDS}
import java.util.Formatter
import nasa.nccs.cdapi.kernels.AxisIndices
import nasa.nccs.cdapi.tensors.{CDArray, CDByteArray, CDFloatArray}
import nasa.nccs.esgf.utilities.numbers.GenericNumber
import nasa.nccs.utilities.cdsutils
import ucar.nc2.time.{CalendarDate, CalendarDateRange}
import ucar.{ma2, nc2 }
import ucar.nc2.constants.AxisType
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable

object FragmentSelectionCriteria extends Enumeration { val Largest, Smallest = Value }

trait DataLoader {
  def getDataset( collection: String, varName: String ): CDSDataset
  def getVariable( collection: String, varName: String ): CDSVariable
  def getFragment( fragSpec: DataFragmentSpec, abortSizeFraction: Float=0f ): PartitionedFragment
  def findEnclosingFragSpecs( fKeyChild: DataFragmentKey, admitEquality: Boolean = true): Set[DataFragmentKey]
  def findEnclosedFragSpecs( fKeyParent: DataFragmentKey, admitEquality: Boolean = true): Set[DataFragmentKey]
}

trait ScopeContext {
  def getConfiguration: Map[String,String]
  def config( key: String, default: String ): String = getConfiguration.getOrElse(key,default)
  def config( key: String ): Option[String] = getConfiguration.get(key)
}

class RequestContext( val domains: Map[String,DomainContainer], val inputs: Map[String, OperationInputSpec], val targetGrid: TargetGrid, private val configuration: Map[String,String] ) extends ScopeContext {
  def getConfiguration = configuration
  def missing_variable(uid: String) = throw new Exception("Can't find Variable '%s' in uids: [ %s ]".format(uid, inputs.keySet.mkString(", ")))
  def getDataSources: Map[String, OperationInputSpec] = inputs
  def getInputSpec( uid: String = "" ): OperationInputSpec = inputs.get( uid ) match {
    case Some(inputSpec) => inputSpec
    case None => inputs.head._2
  }
  def getDataset( serverContext: ServerContext, uid: String = "" ): CDSDataset = inputs.get( uid ) match {
    case Some(inputSpec) => inputSpec.data.getDataset(serverContext)
    case None =>inputs.head._2.data.getDataset(serverContext)
  }
  def getSection( serverContext: ServerContext, uid: String = "" ): ma2.Section = inputs.get( uid ) match {
    case Some(inputSpec) => inputSpec.data.roi
    case None =>inputs.head._2.data.roi
  }
  def getAxisIndices( uid: String ): AxisIndices = inputs.get(uid) match {
    case Some(inputSpec) => inputSpec.axes
    case None => missing_variable(uid)
  }
  def getDomain(domain_id: String): DomainContainer = domains.get(domain_id) match {
    case Some(domain_container) => domain_container
    case None => throw new Exception("Undefined domain in ExecutionContext: " + domain_id)
  }
}

case class OperationInputSpec( data: DataFragmentSpec, axes: AxisIndices ) {
  def getRange( dimension_name: String ): Option[ma2.Range] = data.getRange( dimension_name )
}

class GridCoordSpec( val index: Int, val variable: CDSVariable, val coordAxis: CoordinateAxis1D, val domainAxisOpt: Option[DomainAxis] ) {
  val logger = org.slf4j.LoggerFactory.getLogger("nasa.nccs.cds2.cdm.GridCoordSpec")
  private val range: ma2.Range = getAxisRange( variable, coordAxis, domainAxisOpt )
  private lazy val _data = getCoordinateValues
  val bounds: Array[Float] = getAxisBounds( coordAxis, domainAxisOpt)
  def getData: ma2.Array = _data
  def getAxisType: AxisType = coordAxis.getAxisType
  def getCFAxisName: String = coordAxis.getAxisType.getCFAxisName
  def getAxisName: String = coordAxis.getShortName
  def getIndexRange: ma2.Range = range

  private def getAxisRange( variable: CDSVariable, coordAxis: CoordinateAxis, domainAxisOpt: Option[DomainAxis]): ma2.Range = domainAxisOpt match {
    case Some( domainAxis ) =>  domainAxis.system match {
      case asys if asys.startsWith("ind") => new ma2.Range( coordAxis.getAxisType.getCFAxisName, domainAxis.start.toInt, domainAxis.end.toInt, 1 )
      case asys if asys.startsWith("val") => getGridIndexBounds( domainAxis.start, domainAxis.end )
      case _ => throw new IllegalStateException("CDSVariable: Illegal system value in axis bounds: " + domainAxis.system)
    }
    case None => new ma2.Range( coordAxis.getAxisType.getCFAxisName, 0, coordAxis.getShape(0), 1 )
  }
  private def getAxisBounds( coordAxis: CoordinateAxis, domainAxisOpt: Option[DomainAxis]): Array[Float] = domainAxisOpt match {
    case Some( domainAxis ) =>  domainAxis.system match {
      case asys if asys.startsWith("ind") => Array( _data.getFloat(0), _data.getFloat(_data.getSize.toInt-1), 1 )
      case asys if asys.startsWith("val") => Array( domainAxis.start.toFloat, domainAxis.end.toFloat, 1 )
      case _ => throw new IllegalStateException("CDSVariable: Illegal system value in axis bounds: " + domainAxis.system)
    }
    case None => Array( coordAxis.getMinValue.toFloat, coordAxis.getMaxValue.toFloat )
  }

  def getBounds( range: ma2.Range ): Array[Float] = Array( _data.getFloat(range.first), _data.getFloat(range.last ) )

  def getBoundedCalDate(coordAxis1DTime: CoordinateAxis1DTime, caldate: CalendarDate, role: BoundsRole.Value, strict: Boolean = true): CalendarDate = {
    val date_range: CalendarDateRange = coordAxis1DTime.getCalendarDateRange
    if (!date_range.includes(caldate)) {
      if (strict) throw new IllegalStateException("CDS2-CDSVariable: Time value %s outside of time bounds %s".format(caldate.toString, date_range.toString))
      else {
        if (role == BoundsRole.Start) {
          val startDate: CalendarDate = date_range.getStart
          logger.warn("Time value %s outside of time bounds %s, resetting value to range start date %s".format(caldate.toString, date_range.toString, startDate.toString))
          startDate
        } else {
          val endDate: CalendarDate = date_range.getEnd
          logger.warn("Time value %s outside of time bounds %s, resetting value to range end date %s".format(caldate.toString, date_range.toString, endDate.toString))
          endDate
        }
      }
    } else caldate
  }

  def getCoordinateValues: ma2.Array = coordAxis.getAxisType match {
      case AxisType.Time =>
        val units = "seconds since 1970-1-1"
        val timeAxis: CoordinateAxis1DTime = CoordinateAxis1DTime.factory( variable.dataset.ncDataset, coordAxis, new Formatter() )
        val timeCalValues: List[CalendarDate] = timeAxis.getCalendarDates.toList
        val timeZero = CalendarDate.of(timeCalValues.head.getCalendar, 1970, 1, 1, 1, 1, 1)
        val time_values = for (index <- (range.first() to range.last() by range.stride()); calVal = timeCalValues(index)) yield calVal.getDifferenceInMsecs(timeZero).toFloat / 1000f
        ma2.Array.factory( ma2.DataType.FLOAT, Array(time_values.length), time_values.toArray[Float] )
      case x => coordAxis.read( List(range) )
    }


  def getTimeAxis: CoordinateAxis1DTime = coordAxis match {
    case coordAxis1DTime: CoordinateAxis1DTime => coordAxis1DTime
    case variableDS: VariableDS => CoordinateAxis1DTime.factory( variable.dataset.ncDataset, variableDS, new Formatter() )
    case x => throw new IllegalStateException("CDS2-CDSVariable: Can't create time axis from type type: %s ".format(coordAxis.getClass.getName))
  }

  def getTimeCoordIndex( tval: String, role: BoundsRole.Value, strict: Boolean = true): Int = {
    val coordAxis1DTime: CoordinateAxis1DTime = getTimeAxis
    val caldate: CalendarDate = cdsutils.dateTimeParser.parse(tval)
    val caldate_bounded: CalendarDate = getBoundedCalDate(coordAxis1DTime, caldate, role, strict)
    coordAxis1DTime.findTimeIndexFromCalendarDate(caldate_bounded)
  }

  def getTimeIndexBounds( startval: String, endval: String, strict: Boolean = true): ma2.Range = {
    val startIndex = getTimeCoordIndex( startval, BoundsRole.Start, strict)
    val endIndex = getTimeCoordIndex( endval, BoundsRole.End, strict )
    new ma2.Range( coordAxis.getAxisType.getCFAxisName, startIndex, endIndex)
  }

  def getNormalizedCoordinate( cval: Double ): Double = coordAxis.getAxisType match {
    case nc2.constants.AxisType.Lon =>
      if( (cval<0.0) && ( coordAxis.getMinValue >= 0.0 ) ) cval + 360.0
      else if( (cval>180.0) && ( coordAxis.getMaxValue <= 180.0 ) ) cval - 360.0
      else cval
    case x => cval
  }

  def getGridCoordIndex(cval: Double, role: BoundsRole.Value, strict: Boolean = true): Int = {
    val coordAxis1D = CDSVariable.toCoordAxis1D( coordAxis )
    coordAxis1D.findCoordElement( getNormalizedCoordinate( cval ) ) match {
      case -1 =>
        if (role == BoundsRole.Start) {
          val grid_start = coordAxis1D.getCoordValue(0)
          logger.warn("Axis %s: ROI Start value %f outside of grid area, resetting to grid start: %f".format(coordAxis.getShortName, cval, grid_start))
          0
        } else {
          val end_index = coordAxis1D.getSize.toInt - 1
          val grid_end = coordAxis1D.getCoordValue(end_index)
          logger.warn("Axis %s: ROI Start value %s outside of grid area, resetting to grid end: %f".format(coordAxis.getShortName, cval, grid_end))
          end_index
        }
      case ival => ival
    }
  }
  def getNumericCoordValues( range: ma2.Range ): Array[Double] = {
    val coord_values = coordAxis.getCoordValues;
    if (coord_values.length == range.length) coord_values else (0 until range.length).map(iE => coord_values(range.element(iE))).toArray
  }

  def getGridIndexBounds( startval: Double, endval: Double, strict: Boolean = true): ma2.Range = {
    val startIndex = getGridCoordIndex(startval, BoundsRole.Start, strict)
    val endIndex = getGridCoordIndex(endval, BoundsRole.End, strict)
    new ma2.Range( coordAxis.getAxisType.getCFAxisName, startIndex, endIndex )
  }

  def getIndexBounds( startval: GenericNumber, endval: GenericNumber, strict: Boolean = true): ma2.Range = {
    val indexRange = if (coordAxis.getAxisType == nc2.constants.AxisType.Time) getTimeIndexBounds( startval.toString, endval.toString ) else getGridIndexBounds( startval, endval)
    assert(indexRange.last >= indexRange.first, "CDS2-CDSVariable: Coordinate bounds appear to be inverted: start = %s, end = %s".format(startval.toString, endval.toString))
    indexRange
  }
}

object GridSpec {
    def apply( variable: CDSVariable, roiOpt: Option[List[DomainAxis]] ): GridSpec = {
      val coordSpecs = for (idim <- variable.dims.indices; dim = variable.dims(idim); coord_axis = variable.getCoordinateAxis(dim.getFullName)) yield {
        val domainAxisOpt: Option[DomainAxis] = roiOpt.flatMap(axes => axes.filter(da => da.matches( coord_axis.getAxisType )).headOption)
        new GridCoordSpec(idim, variable, coord_axis, domainAxisOpt)
      }
      new GridSpec( variable, coordSpecs )
    }
}

class  GridSpec( variable: CDSVariable, protected val axes: IndexedSeq[GridCoordSpec] ) {
  val logger = org.slf4j.LoggerFactory.getLogger("nasa.nccs.cds2.cdm.GridCoordSpec")
  def getAxisSpec( dim_index: Int ): GridCoordSpec = axes(dim_index)
  def getAxisSpec( axis_type: AxisType ): Option[GridCoordSpec] = axes.filter( axis => axis.getAxisType == axis_type ).headOption
  def getAxisSpec( domainAxis: DomainAxis ): Option[GridCoordSpec] = axes.filter( axis => domainAxis.matches(axis.getAxisType ) ).headOption
  def getAxisSpec( cfAxisName: String ): Option[GridCoordSpec] = axes.filter( axis => axis.getCFAxisName.toLowerCase == cfAxisName.toLowerCase ).headOption
  def getRank = axes.length
  def getBounds: Option[Array[Float]] = getAxisSpec("x").flatMap( xaxis => getAxisSpec("y").map( yaxis => Array( xaxis.bounds(0), yaxis.bounds(0), xaxis.bounds(1), yaxis.bounds(1) )) )

  def getSection: ma2.Section = new ma2.Section( axes.map( _.getIndexRange ): _* )

  def getSubSection( roi: List[DomainAxis] ): ma2.Section = {
    val ranges: IndexedSeq[ma2.Range] = for( gridCoordSpec <- axes ) yield {
      roi.filter( _.matches( gridCoordSpec.getAxisType ) ).headOption match {
        case Some( domainAxis ) => domainAxis.system match {
          case asys if asys.startsWith( "ind" ) => new ma2.Range( gridCoordSpec.getCFAxisName, domainAxis.start.toInt, domainAxis.end.toInt, 1)
          case asys if asys.startsWith( "val" ) => gridCoordSpec.getGridIndexBounds( domainAxis.start, domainAxis.end )
          case _ => throw new IllegalStateException("CDSVariable: Illegal system value in axis bounds: " + domainAxis.system)
        }
        case None => gridCoordSpec.getIndexRange
      }
    }
    new ma2.Section( ranges: _* )
  }

}


class TargetGrid( val variable: CDSVariable, roiOpt: Option[List[DomainAxis]] ) extends CDSVariable(variable.name, variable.dataset, variable.ncVariable ) {
  val grid = GridSpec( variable, roiOpt )
  val coordAxes: List[ CoordinateAxis1D ] = variable.getCoordinateAxes
  def getCoordAxis( dimIndex: Int ): CoordinateAxis1D = coordAxes( dimIndex  )

  def createFragmentSpec( data_variable: CDSVariable, section: ma2.Section, mask: Option[String] = None, partIndex: Int=0, partAxis: Char='*', nPart: Int=1 ) = {
    val partitions = if ( partAxis == '*' ) List.empty[PartitionSpec] else {
      val partitionAxis = dataset.getCoordinateAxis(partAxis)
      val partAxisIndex = ncVariable.findDimensionIndex(partitionAxis.getShortName)
      assert(partAxisIndex != -1, "CDS2-CDSVariable: Can't find axis %s in variable %s".format(partitionAxis.getShortName, ncVariable.getNameAndDimensions))
      List( new PartitionSpec(partAxisIndex, nPart, partIndex) )
    }
    new DataFragmentSpec( data_variable.name, data_variable.dataset.name, Some(this), data_variable.ncVariable.getDimensionsString(), data_variable.ncVariable.getUnitsString, data_variable.getAttributeValue( "long_name", ncVariable.getFullName ), section, mask, partitions.toArray )
  }

    def getAxisIndices( axisConf: List[OperationSpecs], flatten: Boolean = false ): AxisIndices = {
      val axis_ids = mutable.HashSet[Int]()
      for( opSpec <- axisConf ) {
        val axes = opSpec.getSpec("axes")
        val axis_chars: List[Char] = if( axes.contains(',') ) axes.split(",").map(_.head).toList else axes.toList
        axis_ids ++= axis_chars.map( cval => getAxisIndex( cval.toString ) )
      }
      val axisIdSet = if(flatten) axis_ids.toSet else  axis_ids.toSet
      new AxisIndices( axisIds=axisIdSet )
    }

    def getAxisIndex( cfAxisName: String, default_val: Int = -1 ): Int = grid.getAxisSpec( cfAxisName.toLowerCase ).map( gcs => gcs.index ).getOrElse( default_val )


    def getCFAxisName( dimension_index: Int, default_val: String ): String = {
      val dim: nc2.Dimension = ncVariable.getDimension(dimension_index)
      dataset.findCoordinateAxis(dim.getFullName) match {
        case Some(axis) => axis.getAxisType.getCFAxisName
        case None => default_val
      }
    }

  def getBounds( section: ma2.Section ): Option[Array[Float]] = {
    val xrangeOpt: Option[Array[Float]] = Option(section.find("x")).flatMap( (r: ma2.Range) => grid.getAxisSpec("x").map( (gs: GridCoordSpec) => gs.getBounds(r) ) )
    val yrangeOpt: Option[Array[Float]] = Option(section.find("y")).flatMap( (r: ma2.Range) => grid.getAxisSpec("y").map( (gs: GridCoordSpec) => gs.getBounds(r) ) )
    xrangeOpt.flatMap( xrange => yrangeOpt.map( yrange => Array( xrange(0), yrange(0), xrange(1), yrange(1) )) )
  }

  def createFragmentSpec() = new DataFragmentSpec( variable.name, dataset.name, Some(this) )

  def loadPartition( data_variable: CDSVariable, fragmentSpec : DataFragmentSpec, maskOpt: Option[CDByteArray], axisConf: List[OperationSpecs] ): PartitionedFragment = {
    val partition = fragmentSpec.partitions.head
    val sp = new SectionPartitioner(fragmentSpec.roi, partition.nPart)
    sp.getPartition(partition.partIndex, partition.axisIndex ) match {
      case Some(partSection) =>
        val array = data_variable.read(partSection)
        val cdArray: CDFloatArray = CDFloatArray.factory(array, variable.missing )
        createPartitionedFragment( fragmentSpec, maskOpt, cdArray )
      case None =>
        logger.warn("No fragment generated for partition index %s out of %d parts".format(partition.partIndex, partition.nPart))
        new PartitionedFragment()
    }
  }

  def loadRoi( data_variable: CDSVariable, fragmentSpec: DataFragmentSpec, maskOpt: Option[CDByteArray] ): PartitionedFragment = {
    val array: ma2.Array = data_variable.read(fragmentSpec.roi)
    val cdArray: CDFloatArray = CDFloatArray.factory(array, data_variable.missing, maskOpt )
    createPartitionedFragment( fragmentSpec, maskOpt, cdArray )
  }

  def createPartitionedFragment( fragmentSpec: DataFragmentSpec, maskOpt: Option[CDByteArray], ndArray: CDFloatArray ): PartitionedFragment =  {
    new PartitionedFragment( ndArray, maskOpt, fragmentSpec )
  }


}

class ServerContext( val dataLoader: DataLoader, private val configuration: Map[String,String] )  extends ScopeContext {
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
  def getConfiguration = configuration
  def inputs( inputSpecs: List[OperationInputSpec] ): List[KernelDataInput] = for( inputSpec <- inputSpecs ) yield new KernelDataInput( getVariableData(inputSpec.data), inputSpec.axes )
  def getVariable(fragSpec: DataFragmentSpec ): CDSVariable = dataLoader.getVariable( fragSpec.collection, fragSpec.varname )
  def getVariable(collection: String, varname: String ): CDSVariable = dataLoader.getVariable( collection, varname )
  def getVariableData( fragSpec: DataFragmentSpec ): PartitionedFragment = dataLoader.getFragment( fragSpec )

  def getAxisData( fragSpec: DataFragmentSpec, axis: Char ): ( Int, ma2.Array ) = {
    val variable: CDSVariable = dataLoader.getVariable( fragSpec.collection, fragSpec.varname )
    val coordAxis = variable.dataset.getCoordinateAxis( axis )
    val axisIndex = variable.ncVariable.findDimensionIndex(coordAxis.getShortName)
    val range = fragSpec.roi.getRange( axisIndex )
    ( axisIndex -> coordAxis.read( List(range) ) )
  }

  def createTargetGrid( dataContainer: DataContainer, domainContainerOpt: Option[DomainContainer] ) = {
    val roiOpt: Option[List[DomainAxis]] = domainContainerOpt.map( domainContainer => domainContainer.axes )
    val source = dataContainer.getSource
    val variable: CDSVariable = dataLoader.getVariable( source.collection, source.name )
    new TargetGrid( variable, roiOpt )
  }

  def getDataset(collection: String, varname: String ): CDSDataset = dataLoader.getDataset( collection, varname )



//  def getAxes( fragSpec: DataFragmentSpec ) = {
//    val variable: CDSVariable = dataLoader.getVariable( fragSpec.collection, fragSpec.varname )
//    for( range <- fragSpec.roi.getRanges ) {
//      variable.getCoordinateAxis()
//    }
//
//  }
//    val dataset: CDSDataset = getDataset( fragSpec.collection,  fragSpec.varname  )
//    val coordAxes = dataset.getCoordinateAxes
// //   val newCoordVars: List[GridCoordSpec] = (for (coordAxis <- coordAxes) yield inputSpec.getRange(coordAxis.getShortName) match {
// //     case Some(range) => Some( new GridCoordSpec( coordAxis, range, ) ) )
////      case None => None
////    }).flatten
//    dataset.getCoordinateAxes
//  }
  def computeAxisSpecs( fragSpec: DataFragmentSpec, axisConf: List[OperationSpecs] ): AxisIndices = {
    val variable: CDSVariable = getVariable(fragSpec)
    variable.getAxisIndices( axisConf )
  }

  def getSubset( fragSpec: DataFragmentSpec, new_domain_container: DomainContainer ): PartitionedFragment = {
    val t0 = System.nanoTime
    val baseFragment = dataLoader.getFragment( fragSpec )
    val t1 = System.nanoTime
    val variable = getVariable( fragSpec )
    val targetGrid = fragSpec.targetGridOpt match { case Some(tg) => tg; case None => new TargetGrid( variable, Some(fragSpec.getAxes) ) }
    val newFragmentSpec = targetGrid.createFragmentSpec( variable, targetGrid.grid.getSubSection(new_domain_container.axes),  new_domain_container.mask )
    val rv = baseFragment.cutIntersection( newFragmentSpec.roi )
    val t2 = System.nanoTime
    logger.info( " GetSubsetT: %.4f %.4f".format( (t1-t0)/1.0E9, (t2-t1)/1.0E9 ) )
    rv
  }

  def loadVariableData( dataContainer: DataContainer, domain_container_opt: Option[DomainContainer], targetGrid: TargetGrid ): (String, OperationInputSpec) = {
    val data_source: DataSource = dataContainer.getSource
    val t0 = System.nanoTime
    val variable: CDSVariable = dataLoader.getVariable(data_source.collection, data_source.name)
    val t1 = System.nanoTime
    val axisSpecs: AxisIndices = targetGrid.getAxisIndices( dataContainer.getOpSpecs )
    val t2 = System.nanoTime
    val maskOpt: Option[String] = domain_container_opt.map( domain_container => domain_container.mask ).flatten
    val fragSpec: DataFragmentSpec = targetGrid.createFragmentSpec( variable, targetGrid.grid.getSection, maskOpt )
    dataLoader.getFragment( fragSpec, 0.3f )
    val t3 = System.nanoTime
    logger.info( " loadVariableDataT: %.4f %.4f ".format( (t1-t0)/1.0E9, (t3-t2)/1.0E9 ) )
    return ( dataContainer.uid -> new OperationInputSpec( fragSpec, axisSpecs )  )
  }
}

